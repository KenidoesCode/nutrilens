"""Recognition engines and engine selection."""

from __future__ import annotations

import json

import numpy as np
import pytest

from nutrilens_ml.domain import ConfidenceBand
from nutrilens_ml.inference.base import FoodRecognizer, InferenceError
from nutrilens_ml.inference.factory import EngineConfig, build_recognizer
from nutrilens_ml.inference.heuristic import (
    MAX_HEURISTIC_CONFIDENCE,
    HeuristicFoodRecognizer,
)
from nutrilens_ml.inference.onnx_engine import OnnxFoodRecognizer, OnnxLabelMap, softmax


class TestHeuristicRecognizer:
    def test_implements_the_contract(self):
        assert isinstance(HeuristicFoodRecognizer(), FoodRecognizer)

    def test_reports_engine_and_catalog_version(self, catalog):
        recognizer = HeuristicFoodRecognizer(catalog)
        assert recognizer.name == "heuristic-color-texture"
        assert catalog.dataset_version in recognizer.model_version

    def test_detects_the_dishes_on_a_synthetic_plate(self, plate_image):
        detections = HeuristicFoodRecognizer().recognize(plate_image)
        assert len(detections) == 3

    def test_detections_are_sorted_by_confidence(self, plate_image):
        detections = HeuristicFoodRecognizer().recognize(plate_image)
        scores = [d.confidence for d in detections]
        assert scores == sorted(scores, reverse=True)

    def test_never_claims_high_confidence(self, plate_image):
        # A rule-based match must not be presented with a model's authority.
        for detection in HeuristicFoodRecognizer().recognize(plate_image):
            assert detection.confidence <= MAX_HEURISTIC_CONFIDENCE
            assert detection.confidence_band is not ConfidenceBand.HIGH

    def test_is_deterministic(self, plate_image):
        recognizer = HeuristicFoodRecognizer()
        first = [(d.label, d.confidence) for d in recognizer.recognize(plate_image)]
        second = [(d.label, d.confidence) for d in recognizer.recognize(plate_image)]
        assert first == second

    def test_returns_nothing_for_a_featureless_frame(self, blank_image):
        assert HeuristicFoodRecognizer().recognize(blank_image) == []

    def test_every_detection_carries_its_evidence(self, plate_image):
        for detection in HeuristicFoodRecognizer().recognize(plate_image):
            assert detection.attributes["food_key"]
            assert "mean_hue_deg" in detection.attributes
            assert detection.engine == "heuristic-color-texture"


class TestSoftmax:
    def test_sums_to_one(self):
        assert float(softmax(np.array([1.0, 2.0, 3.0])).sum()) == pytest.approx(1.0)

    def test_is_stable_for_large_logits(self):
        result = softmax(np.array([1000.0, 1001.0]))
        assert np.isfinite(result).all()
        assert float(result.sum()) == pytest.approx(1.0)

    def test_preserves_ordering(self):
        result = softmax(np.array([0.1, 5.0, -2.0]))
        assert int(np.argmax(result)) == 1


class TestOnnxLabelMap:
    def _write(self, tmp_path, payload):
        path = tmp_path / "labels.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_loads_a_valid_map(self, tmp_path):
        path = self._write(
            tmp_path,
            {"model_version": "v1", "labels": ["rice", "dal"], "input_name": "images"},
        )
        label_map = OnnxLabelMap.load(path)
        assert label_map.labels == ("rice", "dal")
        assert label_map.input_name == "images"
        assert label_map.input_layout == "NCHW"

    def test_rejects_an_empty_label_list(self, tmp_path):
        with pytest.raises(InferenceError) as excinfo:
            OnnxLabelMap.load(self._write(tmp_path, {"labels": []}))
        assert excinfo.value.code == "MODEL_LABELS_EMPTY"

    def test_rejects_an_unsupported_layout(self, tmp_path):
        with pytest.raises(InferenceError) as excinfo:
            OnnxLabelMap.load(self._write(tmp_path, {"labels": ["rice"], "input_layout": "NCWH"}))
        assert excinfo.value.code == "MODEL_LAYOUT_UNSUPPORTED"

    def test_rejects_zero_std(self, tmp_path):
        with pytest.raises(InferenceError) as excinfo:
            OnnxLabelMap.load(self._write(tmp_path, {"labels": ["rice"], "std": [0.0, 0.2, 0.2]}))
        assert excinfo.value.code == "MODEL_NORMALIZATION_INVALID"


class TestOnnxEngineWithoutWeights:
    def test_reports_a_missing_model_clearly(self, tmp_path, plate_image):
        engine = OnnxFoodRecognizer(tmp_path / "absent.onnx", tmp_path / "absent.json")
        with pytest.raises(InferenceError) as excinfo:
            engine.recognize(plate_image)
        assert excinfo.value.code == "MODEL_NOT_FOUND"

    def test_version_string_is_safe_before_loading(self, tmp_path):
        engine = OnnxFoodRecognizer(tmp_path / "absent.onnx", tmp_path / "absent.json")
        assert "unloaded" in engine.model_version


class TestEngineSelection:
    def test_auto_falls_back_to_the_classical_engine(self):
        assert isinstance(build_recognizer(EngineConfig()), HeuristicFoodRecognizer)

    def test_heuristic_is_selectable_explicitly(self):
        assert isinstance(
            build_recognizer(EngineConfig(engine="heuristic")), HeuristicFoodRecognizer
        )

    def test_onnx_refuses_to_silently_downgrade(self, tmp_path):
        # A deployment that believes it runs a model must never run rules instead.
        config = EngineConfig(
            engine="onnx",
            onnx_model_path=tmp_path / "absent.onnx",
            onnx_label_map_path=tmp_path / "absent.json",
        )
        with pytest.raises(InferenceError) as excinfo:
            build_recognizer(config)
        assert excinfo.value.code == "MODEL_NOT_CONFIGURED"

    def test_auto_selects_onnx_when_files_exist(self, tmp_path):
        model = tmp_path / "model.onnx"
        labels = tmp_path / "labels.json"
        model.write_bytes(b"not-a-real-model")
        labels.write_text(json.dumps({"labels": ["rice"]}), encoding="utf-8")
        config = EngineConfig(engine="auto", onnx_model_path=model, onnx_label_map_path=labels)
        assert isinstance(build_recognizer(config), OnnxFoodRecognizer)

    def test_unknown_engine_is_rejected(self):
        with pytest.raises(InferenceError) as excinfo:
            build_recognizer(EngineConfig(engine="telepathy"))
        assert excinfo.value.code == "UNKNOWN_ENGINE"
