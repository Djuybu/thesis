/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.util;

import com.dremio.common.AutoCloseables;
import com.dremio.common.SuppressForbidden;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.google.common.base.Preconditions;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.complex.BaseRepeatedValueVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.util.TransferPair;

/**
 * A vectorized list that implements the List interface and stores Java elements using Arrow
 * vectors. It provides the following features:
 *
 * <p>1. Automatic Schema Mapping – Uses reflection to map Java class fields to Arrow vectors. 2.
 * Two Insertion Modes: - Batch Insertion Mode – Transfers ownership of an incoming Arrow vector
 * batch to the internal Arrow vectors without data copying. - Single-Row Insertion Mode – Appends
 * individual element field values to the corresponding vectors. 3. Efficient Retrieval – The
 * `get(index)` method constructs a Java object and populates its fields/sub-fields with values from
 * the corresponding Arrow vectors. 4. Field-Level Access – Supports getting and setting values at
 * the field level. 5. Generic Type Support – Allows the use of parameterized types.
 */
public abstract class VectorizedList<T> extends AbstractList<T> implements AutoCloseable {
  private VectorContainer vectorContainer;
  private int size;
  private Class<?> elementType;

  // generic type maps for generic type resolving
  private Map<Type, Type> genericTypeMap;

  // schema path of java class fields to arrow's vectors
  private Map<SchemaPath, ValueVector> javaFieldToArrowVectorMap = new HashMap<>();

  // transfer ownership from incoming data
  private List<TransferPair> transfers = new ArrayList<>();

  public VectorizedList(BufferAllocator allocator) {
    this.size = 0;
    this.vectorContainer = new VectorContainer(allocator);
  }

  public void setup(T element, Map<SchemaPath, ValueVector> incoming) {
    elementType = element.getClass();
    // generate generic type mapping
    genericTypeMap = TypeCollector.collect(element, elementType);

    // create batchSchema from a list element java object
    BatchSchema batchSchema = toBatchSchema(element);

    // add schema to vectorContainer and create arrow vectors
    vectorContainer.addSchema(batchSchema);
    vectorContainer.buildSchema();
    vectorContainer.allocateNew();

    // create java fields to arrow vector map
    javaFieldToArrowVectorMap = ValueVectorMapper.mapValueVectors(vectorContainer);

    // hook up incoming data
    if (incoming != null) {
      for (Map.Entry<SchemaPath, ValueVector> field : incoming.entrySet()) {
        ValueVector vvIn = field.getValue();
        ValueVector vvOut = javaFieldToArrowVectorMap.get(field.getKey());
        Preconditions.checkNotNull(vvOut);
        TransferPair tp = vvIn.makeTransferPair(vvOut);
        transfers.add(tp);
      }
    }
  }

  /** Add a new batch of data */
  public void addBatch(int records) {
    // transfer ownership, no copying
    transfers.forEach(TransferPair::transfer);
    size += records;
    vectorContainer.setAllCount(size);
  }

  @Override
  public boolean add(T element) {
    // set up if not yet
    if (!vectorContainer.hasSchema()) {
      setup(element, null);
    }

    add(size++, element);
    vectorContainer.setAllCount(size);
    return true;
  }

  /** Add a new element to corresponding vector at index */
  @Override
  public void add(int index, T element) {
    JavaObjectToArrowValueVectorSetter.set(element, elementType, javaFieldToArrowVectorMap, index);
  }

  @Override
  public int size() {
    return size;
  }

  /***
   * get java object from arrow vectors
   */
  @Override
  public T get(int index) {
    Object value =
        JavaObjectGetter.get(elementType, javaFieldToArrowVectorMap, index, genericTypeMap);
    return (T) value;
  }

  // get the value of a field at the index of corresponding arrow vector
  public Object getValue(SchemaPath field, int index) {
    ValueVector vector = javaFieldToArrowVectorMap.get(field);
    return VectorUtil.getValueAt(vector, index);
  }

  // set the value of a field at the index of corresponding arrow vector
  public void setValue(SchemaPath field, int index, Object value) {
    ValueVector vector = javaFieldToArrowVectorMap.get(field);
    VectorUtil.setValue(vector, index, value);
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(vectorContainer);
  }

  // Map all fields of a Java class as Arrow Fields
  private BatchSchema toBatchSchema(T element) {
    SchemaBuilder builder = BatchSchema.newBuilder();

    List<Field> arrowFields = ArrowFieldMapperVisitor.toArrowFields(element, elementType);

    arrowFields.stream().forEach(field -> builder.addField(field));
    return builder.build();
  }

  public static SchemaPath appendSegment(SchemaPath schemaPath, String segment) {
    Preconditions.checkNotNull(segment);
    List<String> segments =
        schemaPath != null ? new ArrayList<>(schemaPath.getNameSegments()) : new ArrayList<>();
    segments.add(segment);
    return SchemaPath.getCompoundPath(segments.toArray(new String[0]));
  }

  /** Base Visitor class for traversing fields of a given Java class. */
  private abstract static class FieldVisitor<T> {

    /**
     * Entry point to visit a type and its fields.
     *
     * @param type The type to visit.
     * @param typeArgs Map of generic type variables to actual types.
     */
    public final T visitType(
        SchemaPath schemaPath, Object javaObject, Type type, Map<Type, Type> typeArgs) {
      if (type == null) {
        return null;
      }

      if (type instanceof Class<?>) {
        return visitClass(schemaPath, javaObject, (Class<?>) type, typeArgs);
      } else if (type instanceof ParameterizedType) {
        return visitParameterizedType(schemaPath, javaObject, (ParameterizedType) type, typeArgs);
      }

      return null;
    }

    /**
     * Visit a class and its fields.
     *
     * @param clazz The class to visit.
     * @param typeArgs Map of generic type variables to actual types.
     */
    private T visitClass(
        SchemaPath schemaPath, Object javaObject, Class<?> clazz, Map<Type, Type> typeArgs) {
      if (clazz == null || clazz == Object.class) {
        return null;
      }

      Object classJavaObject = javaObject;
      if (classJavaObject == null) {
        classJavaObject = getClassJavaObject(clazz);
      }

      // Visit the superclass first
      Type superClass = clazz.getGenericSuperclass();
      T superClassResult = visitType(schemaPath, classJavaObject, superClass, typeArgs);

      // Visit the current class
      List<T> fieldsResults = visitFields(schemaPath, classJavaObject, clazz, typeArgs);

      // aggregate type(clas) level results
      return aggregateTypeResults(
          schemaPath, classJavaObject, clazz, superClassResult, fieldsResults);
    }

    protected T aggregateTypeResults(
        SchemaPath schemaPath,
        Object javaObject,
        Class<?> clazz,
        T superClassResult,
        List<T> fieldsResults) {
      return null;
    }

    protected Object getClassJavaObject(Class<?> clazz) {
      return null;
    }

    /**
     * Visit a parameterized type.
     *
     * @param parameterizedType The parameterized type to visit.
     * @param typeArgs Map of generic type variables to actual types.
     */
    private T visitParameterizedType(
        SchemaPath schemaPath,
        Object javaObject,
        ParameterizedType parameterizedType,
        Map<Type, Type> typeArgs) {
      Type rawType = parameterizedType.getRawType();
      return visitType(
          schemaPath, javaObject, rawType, resolveTypeVariables(parameterizedType, typeArgs));
    }

    /**
     * Visit all declared fields of a class.
     *
     * @param clazz The class whose fields to visit.
     * @param typeArgs Map of generic type variables to actual types.
     */
    @SuppressForbidden
    private List<T> visitFields(
        SchemaPath schemaPath, Object javaObject, Class<?> clazz, Map<Type, Type> typeArgs) {
      List<T> fieldResults = new ArrayList<>();
      for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
        // skip the enclosing (outer) class instance
        if (field.getName().startsWith("this$")) {
          continue;
        }

        field.setAccessible(true);
        Type resolvedType = resolveType(schemaPath, javaObject, field, typeArgs);
        T fieldResult;
        if (resolvedType instanceof Class<?>) {
          Class<?> filedClazz = (Class<?>) resolvedType;
          // simple types
          if (filedClazz.isPrimitive() || isBuiltInType(filedClazz)) {
            fieldResult = visitField(schemaPath, javaObject, field, resolvedType, typeArgs);
            if (fieldResult != null) {
              fieldResults.add(fieldResult);
            }
            continue;
          }
        }

        // class or types, recursively go into the field
        Object fieldJavaObject = getClassTypeFieldJavaObject(javaObject, field, resolvedType);
        SchemaPath newSchemaPath = appendSegment(schemaPath, field.getName());
        fieldResult = visitType(newSchemaPath, fieldJavaObject, resolvedType, typeArgs);
        if (fieldResult != null) {
          fieldResults.add(fieldResult);
        }
      }
      return fieldResults;
    }

    protected Object getClassTypeFieldJavaObject(
        Object classJavaObject, java.lang.reflect.Field field, Type fieldType) {
      try {
        return classJavaObject == null ? null : field.get(classJavaObject);
      } catch (Exception ex) {
        throw UserException.unsupportedError(ex).buildSilently();
      }
    }

    private boolean isBuiltInType(Class<?> clazz) {
      String packageName = clazz.getPackageName();
      return packageName.startsWith("java.") || packageName.startsWith("javax.");
    }

    /**
     * Visit an individual field. Override to implement custom behavior.
     *
     * @param field The field to visit.
     * @param fieldType The resolved type of the field.
     */
    protected T visitField(
        SchemaPath schemaPath,
        Object javaObject,
        java.lang.reflect.Field field,
        Type fieldType,
        Map<Type, Type> typeArgs) {
      return null;
    }

    /** Resolve generic types to their actual types based on the type arguments map. */
    protected Type resolveType(
        SchemaPath schemaPath,
        Object javaObject,
        java.lang.reflect.Field field,
        Map<Type, Type> typeArgs) {
      Type type = field.getGenericType();

      // actually type
      Class<?> actualType;
      if (javaObject != null) {
        try {
          Object fieldValue = field.get(javaObject);
          if (fieldValue != null) {
            actualType = fieldValue.getClass();
            if (type instanceof TypeVariable) {
              typeArgs.put(type, actualType);
            }
            return actualType;
          }
        } catch (Exception ex) {
          throw UserException.unsupportedError(ex).buildSilently();
        }
      }

      // from generic type map
      while (typeArgs.containsKey(type)) {
        type = typeArgs.get(type);
      }
      return type;
    }

    /**
     * Resolve the type variables of a class or parameterized type.
     *
     * @param type The type to resolve.
     * @param typeArgs Existing type argument map.
     * @return Updated type argument map.
     */
    protected Map<Type, Type> resolveTypeVariables(Type type, Map<Type, Type> typeArgs) {
      Map<Type, Type> resolved = new HashMap<>(typeArgs);

      if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) type;
        Type[] typeParameters = ((Class<?>) parameterizedType.getRawType()).getTypeParameters();
        Type[] actualArguments = parameterizedType.getActualTypeArguments();

        for (int i = 0; i < typeParameters.length; i++) {
          resolved.put(typeParameters[i], actualArguments[i]);
        }
      } else if (type instanceof Class<?>) {
        Class<?> clazz = (Class<?>) type;
        TypeVariable<?>[] typeParameters = clazz.getTypeParameters();

        for (TypeVariable<?> typeParameter : typeParameters) {
          resolved.put(typeParameter, typeParameter.getBounds()[0]);
        }
      }

      return resolved;
    }
  }

  /** Construct a Java object from the vectorContainer and the index */
  private static class JavaObjectGetter extends FieldVisitor<Object> {
    private final Map<SchemaPath, ValueVector> javaFieldToArrowFieldMap;
    private final int index;

    public static Object get(
        Type elementType,
        Map<SchemaPath, ValueVector> javaFieldToArrowFieldMap,
        int index,
        Map<Type, Type> genericTypeMap) {
      VectorizedList.JavaObjectGetter getter =
          new VectorizedList.JavaObjectGetter(javaFieldToArrowFieldMap, index);
      return getter.visitType(null, null, elementType, genericTypeMap);
    }

    public JavaObjectGetter(Map<SchemaPath, ValueVector> javaFieldToArrowFieldMap, int index) {
      this.javaFieldToArrowFieldMap = javaFieldToArrowFieldMap;
      this.index = index;
    }

    @Override
    protected Map<Type, Type> resolveTypeVariables(Type type, Map<Type, Type> typeArgs) {
      return typeArgs;
    }

    @Override
    protected Type resolveType(
        SchemaPath schemaPath, Object javaObject, java.lang.reflect.Field field, Map typeArgs) {
      // from generic type map
      Type type = field.getGenericType();
      while (typeArgs.containsKey(type)) {
        type = (Type) typeArgs.get(type);
      }
      return type;
    }

    @Override
    protected Object visitField(
        SchemaPath schemaPath,
        Object javaObject,
        java.lang.reflect.Field field,
        Type fieldType,
        Map<Type, Type> typeArgs) {
      super.visitField(schemaPath, javaObject, field, fieldType, typeArgs);
      try {
        SchemaPath fieldSchema = appendSegment(schemaPath, field.getName());
        ValueVector valueVector = javaFieldToArrowFieldMap.get(fieldSchema);
        Object value = VectorUtil.getValueAt(valueVector, index);
        field.set(javaObject, value);
      } catch (Exception ex) {
        throw UserException.unsupportedError(ex).buildSilently();
      }

      return null;
    }

    @Override
    protected Object aggregateTypeResults(
        SchemaPath schemaPath,
        Object javaObject,
        Class<?> clazz,
        Object superClassResult,
        List<Object> fieldsResults) {
      return javaObject;
    }

    @Override
    protected Object getClassTypeFieldJavaObject(
        Object classJavaObject, java.lang.reflect.Field field, Type fieldType) {
      try {
        if (fieldType instanceof Class<?>) {
          Object fieldJavaObject = instantiateClass((Class<?>) fieldType);
          field.set(classJavaObject, fieldJavaObject);
          return fieldJavaObject;
        }
      } catch (Exception ex) {
        throw UserException.unsupportedError(ex).buildSilently();
      }
      return null;
    }

    @Override
    protected Object getClassJavaObject(Class<?> clazz) {
      try {
        return instantiateClass(clazz);
      } catch (Exception ex) {
        throw UserException.unsupportedError(ex).buildSilently();
      }
    }

    @SuppressForbidden
    public static <T> T instantiateClass(Class<T> clazz) {
      if (clazz == null) {
        return null;
      }

      try {
        // Try to use the default constructor
        Constructor<T> defaultConstructor = clazz.getDeclaredConstructor();
        defaultConstructor.setAccessible(true); // Handle private constructors
        return defaultConstructor.newInstance();
      } catch (NoSuchMethodException e) {
        // Handle case where no default constructor is found
        // Attempt to use the first available constructor
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        if (constructors.length > 0) {
          Constructor<?> constructor = constructors[0];
          constructor.setAccessible(true); // Handle private constructors

          // Create arguments for the constructor (using nulls for simplicity)
          Object[] args = new Object[constructor.getParameterCount()];
          for (int i = 0; i < args.length; i++) {
            args[i] = getDefaultValue(constructor.getParameterTypes()[i]);
          }

          try {
            return (T) constructor.newInstance(args);
          } catch (Exception ex) {
            throw UserException.unsupportedError(ex).buildSilently();
          }
        }
      } catch (Exception ex) {
        throw UserException.unsupportedError(ex).buildSilently();
      }

      return null;
    }

    /**
     * Provides a default value for a given type.
     *
     * @param type The type.
     * @return A default value for the type.
     */
    private static Object getDefaultValue(Class<?> type) {
      if (type.isPrimitive()) {
        if (type == boolean.class) {
          return false;
        } else if (type == char.class) {
          return '\0';
        } else if (type == byte.class
            || type == short.class
            || type == int.class
            || type == long.class) {
          return 0;
        } else if (type == float.class) {
          return 0.0f;
        } else if (type == double.class) {
          return 0.0;
        }
      }
      return null; // Return null for non-primitive types
    }
  }

  /** Add the field values of a Java object to corresponding arrow vectors */
  private static class JavaObjectToArrowValueVectorSetter extends FieldVisitor<Void> {
    private final Map<SchemaPath, ValueVector> javaFieldToArrowFieldMap;

    private final int index;

    public static void set(
        Object javaObject,
        Type elementType,
        Map<SchemaPath, ValueVector> javaFieldToArrowFieldMap,
        int index) {
      JavaObjectToArrowValueVectorSetter setter =
          new JavaObjectToArrowValueVectorSetter(javaObject, javaFieldToArrowFieldMap, index);
      setter.visitType(null, javaObject, elementType, new HashMap<>());
    }

    public JavaObjectToArrowValueVectorSetter(
        Object javaObject, Map<SchemaPath, ValueVector> javaFieldToArrowFieldMap, int index) {
      this.javaFieldToArrowFieldMap = javaFieldToArrowFieldMap;
      this.index = index;
    }

    @Override
    protected Void visitField(
        SchemaPath schemaPath,
        Object javaObject,
        java.lang.reflect.Field field,
        Type fieldType,
        Map<Type, Type> typeArgs) {
      super.visitField(schemaPath, javaObject, field, fieldType, typeArgs);
      try {
        Object value = field.get(javaObject);

        SchemaPath fieldSchemaPath = appendSegment(schemaPath, field.getName());
        ValueVector valueVector = javaFieldToArrowFieldMap.get(fieldSchemaPath);
        VectorUtil.setValue(valueVector, index, value);
      } catch (Exception ex) {
        throw UserException.unsupportedError(ex).buildSilently();
      }

      return null;
    }
  }

  /** Map Java classes to Apache Arrow Field lists. */
  private static class ArrowFieldMapperVisitor extends FieldVisitor<Field> {

    private final List<Field> arrowFields = new ArrayList<>();

    public static List<Field> toArrowFields(Object javaObject, Type rootType) {
      ArrowFieldMapperVisitor visitor = new ArrowFieldMapperVisitor();
      visitor.visitType(null, javaObject, rootType, new HashMap<>());
      return visitor.getArrowFields();
    }

    public List<Field> getArrowFields() {
      return arrowFields;
    }

    @Override
    protected Field aggregateTypeResults(
        SchemaPath schemaPath,
        Object javaObject,
        Class<?> clazz,
        Field superClassResult,
        List<Field> fieldsResults) {
      List<Field> classLevelFields = new ArrayList<>();
      if (superClassResult != null) {
        List<Field> superClassFields = new ArrayList<>();
        if (superClassResult.getChildren().size() > 0) {
          superClassFields.addAll(superClassResult.getChildren());
        } else {
          superClassFields.add(superClassResult);
        }
        classLevelFields.addAll(superClassFields);
      }

      if (fieldsResults != null) {
        classLevelFields.addAll(fieldsResults);
      }

      Field classLevelField = null;

      // only collect top-level fields
      if (schemaPath == null) {
        classLevelFields.stream().forEach(field -> arrowFields.add(field));
      } else {
        classLevelField =
            new Field(
                schemaPath.getRootSegment().getPath(),
                FieldType.nullable(new ArrowType.Struct()),
                classLevelFields);
      }
      return classLevelField;
    }

    @Override
    protected Field visitField(
        SchemaPath schemaPath,
        Object javaObject,
        java.lang.reflect.Field field,
        Type fieldType,
        Map<Type, Type> typeArgs) {
      super.visitField(schemaPath, javaObject, field, fieldType, typeArgs);
      ArrowType arrowType = getArrowType(fieldType);
      if (arrowType != null) {
        // Primitive type: Create a simple Arrow field
        return new Field(field.getName(), FieldType.nullable(arrowType), null);
      }

      return null;
    }

    private ArrowType getArrowType(Type type) {
      if (type instanceof Class<?>) {
        Class<?> clazz = (Class<?>) type;
        if (clazz == int.class || clazz == Integer.class) {
          return new ArrowType.Int(32, true);
        } else if (clazz == long.class || clazz == Long.class) {
          return new ArrowType.Int(64, true);
        } else if (clazz == float.class || clazz == Float.class) {
          return new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
        } else if (clazz == double.class || clazz == Double.class) {
          return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        } else if (clazz == String.class) {
          return ArrowType.Utf8.INSTANCE;
        } else if (clazz.isArray()) {
          if (clazz.getComponentType() == byte.class) {
            // Special case for byte arrays
            return ArrowType.Binary.INSTANCE;
          }
        } else {
          return null; // Handle other types as needed
        }
      } else if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) type;
        ArrowType rawTypeArrow = getArrowType(parameterizedType.getRawType());

        // Optionally handle parameterized types specifically here
        if (rawTypeArrow != null) {
          return rawTypeArrow;
        }
      }

      return null; // Handle other complex types if needed
    }
  }

  /** Travel vectorContainer to get the schema path to vector mapping */
  private static class ValueVectorMapper {

    /** Recursively maps a list of ValueVectors to a path-to-vector map. */
    public static Map<SchemaPath, ValueVector> mapValueVectors(VectorContainer vectorContainer) {
      Map<SchemaPath, ValueVector> vectorMap = new HashMap<>();
      Iterator<VectorWrapper<?>> iterator = vectorContainer.iterator();
      while (iterator.hasNext()) {
        ValueVector vector = iterator.next().getValueVector();
        mapVector(vector, appendSegment(null, vector.getName()), vectorMap);
      }
      return vectorMap;
    }

    /**
     * Helper method to recursively map a ValueVector and its children.
     *
     * @param vector The current ValueVector.
     * @param path The current path to the ValueVector.
     * @param vectorMap The map to populate with paths and vectors.
     */
    private static void mapVector(
        ValueVector vector, SchemaPath path, Map<SchemaPath, ValueVector> vectorMap) {
      vectorMap.put(path, vector);

      if (vector instanceof StructVector) {
        StructVector structVector = (StructVector) vector;
        for (String childName : structVector.getChildFieldNames()) {
          ValueVector childVector = structVector.getChild(childName);
          mapVector(childVector, appendSegment(path, childName), vectorMap);
        }
      } else if (vector instanceof BaseRepeatedValueVector) {
        BaseRepeatedValueVector repeatedVector = (BaseRepeatedValueVector) vector;
        ValueVector dataVector = repeatedVector.getDataVector();
        if (dataVector != null) {
          mapVector(dataVector, appendSegment(path, "[]"), vectorMap);
        }
      }
    }
  }

  /** Collect generic type mapping */
  private static class TypeCollector extends FieldVisitor {
    private Map<Type, Type> types = new HashMap<>();

    public static Map<Type, Type> collect(Object element, Type rootType) {
      TypeCollector collector = new TypeCollector();
      collector.visitType(null, element, rootType, new HashMap<>());
      return collector.types;
    }

    @Override
    protected Object visitField(
        SchemaPath schemaPath,
        Object javaObject,
        java.lang.reflect.Field field,
        Type fieldType,
        Map typeArgs) {
      types.putAll(typeArgs);
      return super.visitField(schemaPath, javaObject, field, fieldType, typeArgs);
    }
  }
}
