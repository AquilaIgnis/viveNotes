# On-device recognition artifacts

viveNotes includes or downloads the following machine-learning artifacts for offline recognition.

## en_PP-OCRv5_mobile_rec

- Upstream model: <https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec>
- Android ONNX conversion: <https://github.com/GreatV/oar-ocr>
- License: Apache License 2.0
- Bundled graph SHA-256:
  `8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac`
- Bundled dictionary SHA-256:
  `e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6`

## PP-FormulaNet-S

- Upstream model: <https://huggingface.co/PaddlePaddle/PP-FormulaNet-S>
- Android ONNX conversion and tokenizer: <https://github.com/GreatV/oar-ocr>
- ONNX download: <https://github.com/GreatV/oar-ocr/releases/tag/v0.3.0>
- Byte-identical tokenizer mirror:
  <https://huggingface.co/PaddlePaddle/PP-FormulaNet-L_safetensors>
- License: Apache License 2.0
- Downloaded graph SHA-256:
  `0ee32c7bfbd9e586364f89f71860476ccb5334e35674a61f3df5e0553d6a6dcc`
- Downloaded tokenizer SHA-256:
  `2811d82701ec97c192fa256aa2b4516929373870ae660326cc5b1dc879b95ff2`

## ONNX Runtime

- Project: <https://github.com/microsoft/onnxruntime>
- Android package: `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
- License: MIT

The files are used locally. viveNotes does not upload ink or recognition results.
