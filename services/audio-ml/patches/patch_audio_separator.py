import glob
import sys

SITE_PACKAGES_PATTERNS = [
    "/usr/lib/python*/dist-packages",
    "/usr/local/lib/python*/dist-packages",
    "/usr/local/lib/python*/site-packages",
]


def find_roformer_loader():
    for pattern in SITE_PACKAGES_PATTERNS:
        matches = glob.glob(f"{pattern}/audio_separator/separator/roformer/roformer_loader.py")
        if matches:
            return matches[0]
    return None


def patch_file(path):
    with open(path) as f:
        lines = f.readlines()

    patched = False

    for i, line in enumerate(lines):
        # Fix 1: Before each torch.load(model_path, ...), resolve .yaml → .ckpt
        if "torch.load(model_path," in line and "ckpt_resolve" not in lines[max(0, i - 1)]:
            indent = len(line) - len(line.lstrip())
            ws = " " * indent
            resolve_block = (
                f"{ws}# ckpt_resolve: audio-separator passes .yaml path, torch.load needs .ckpt\n"
                f"{ws}if model_path.endswith(('.yaml', '.yml')):\n"
                f"{ws}    _ckpt = os.path.splitext(model_path)[0] + '.ckpt'\n"
                f"{ws}    if os.path.exists(_ckpt):\n"
                f"{ws}        model_path = _ckpt\n"
            )
            lines[i] = resolve_block + line
            patched = True

        # Fix 2: Add weights_only=False to torch.load calls
        if "torch.load(model_path," in lines[i] and "weights_only" not in lines[i]:
            lines[i] = lines[i].rstrip().rstrip(")") + ", weights_only=False)\n"
            patched = True

        # Fix 3: Change weights_only=True to False
        if "weights_only=True" in lines[i]:
            lines[i] = lines[i].replace("weights_only=True", "weights_only=False")
            patched = True

    if patched:
        with open(path, "w") as f:
            f.writelines(lines)
        print(f"Patched: {path}")
    else:
        print(f"No changes needed: {path}")


if __name__ == "__main__":
    loader_path = find_roformer_loader()
    if loader_path is None:
        print("WARNING: roformer_loader.py not found — skipping patch")
        sys.exit(0)
    patch_file(loader_path)
