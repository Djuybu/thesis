#
# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import sys
import joblib

class ReflectionBrain:
    pass # Wait, if joblib requires the original methods, it might fail, but let's try.
    
print("Loading model...")
try:
    with open('services/autonomous-reflection/reflection_brain.pkl', 'rb') as f:
        model = joblib.load(f)
    print("Model loaded.")
    df = model.predict_reflection(['revenue', 'customer_id', 'unknown_col'])
    print(df.to_dict('records'))
except Exception as e:
    print("Error:", e)
