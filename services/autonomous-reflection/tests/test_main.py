import pytest
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "up"

def test_predict_schema():
    payload = {
        "datasetPath": ["space1", "table_sale"],
        "columns": [
            {
                "name": "customer_id",
                "type": "VARCHAR",
                "uniqueCount": 1500
            },
            {
                "name": "price",
                "type": "DOUBLE",
                "uniqueCount": 800
            }
        ]
    }
    
    response = client.post("/predict/schema", json=payload)
    assert response.status_code == 200
    
    data = response.json()
    assert data["datasetPath"] == ["space1", "table_sale"]
    assert "dimensions" in data
    assert "measures" in data
    
    # Do mock logic fallback sẽ tự push VARCHAR vào dimension và DOUBLE vào measure
    assert "customer_id" in data["dimensions"]
    assert "price" in data["measures"]
