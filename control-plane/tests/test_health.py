import importlib
import importlib.util

from fastapi.testclient import TestClient


def test_health_endpoint_reports_service_status() -> None:
    module_spec = importlib.util.find_spec("loanagent.main")
    assert module_spec is not None, "loanagent.main health application is not implemented"

    module = importlib.import_module("loanagent.main")
    response = TestClient(module.app).get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "control-plane"}
