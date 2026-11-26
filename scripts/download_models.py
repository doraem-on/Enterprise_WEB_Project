"""Download checksum-pinned pretrained MiniLM assets; no training or private data upload."""
import hashlib, os, urllib.request
from pathlib import Path
root=Path(os.environ.get('MODEL_DIR','models')); root.mkdir(parents=True,exist_ok=True)
files={
 'minilm.onnx':('onnx/model.onnx','6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452'),
 'tokenizer.json':('tokenizer.json','be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037')}
for name,(remote,expected) in files.items():
 dest=root/name
 if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest()==expected:
  print(f'{name}: verified');continue
 temp=dest.with_suffix('.download')
 urllib.request.urlretrieve('https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/'+remote,temp)
 if hashlib.sha256(temp.read_bytes()).hexdigest()!=expected:
  temp.unlink();raise SystemExit(f'{name}: checksum mismatch; review upstream revision before updating')
 temp.replace(dest);print(f'{name}: downloaded and verified')
