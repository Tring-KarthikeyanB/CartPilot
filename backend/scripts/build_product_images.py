"""Download, square-crop and store catalogue product images under backend/static/products/.

Images are self-hosted by the backend so the app doesn't depend on third-party hotlinking
(Wikimedia rate-limits hotlinks). Sources and licenses: docs/IMAGE_CREDITS.md.

Usage (from backend/):  python -m pip install -r requirements-dev.txt
                        python scripts/build_product_images.py
"""

import io
import sys
import time
from pathlib import Path

import httpx
from PIL import Image

OUT_DIR = Path(__file__).resolve().parents[1] / "static" / "products"
SIZE = 600
UA = {"User-Agent": "CartPilotImageBuild/0.1 (https://github.com/Tring-KarthikeyanB/CartPilot; hackathon demo) httpx"}


def unsplash(photo_id: str) -> str:
    return f"https://images.unsplash.com/photo-{photo_id}?w=900&q=85&fm=jpg"


def wikimedia(path: str) -> str:
    # path like "4/43/Laptop_stand.jpg"
    name = path.rsplit("/", 1)[-1]
    return f"https://thumb.wikimedia.org/wikipedia/commons/thumb/{path}/960px-{name}"


SOURCES: dict[str, str] = {
    # Keyboards
    "kb-001": unsplash("1541140532154-b024d705b90a"),
    "kb-002": unsplash("1587829741301-dc798b83add3"),
    "kb-003": unsplash("1595225476474-87563907a212"),
    "kb-004": unsplash("1601445638532-3c6f6c3aa1d6"),
    "kb-005": unsplash("1618384887929-16ec33fab9ef"),
    "kb-006": unsplash("1626958390898-162d3577f293"),
    "kb-007": unsplash("1555532538-dcdbd01d373d"),
    # Mice
    "ms-001": unsplash("1527864550417-7fd91fc51a46"),
    "ms-002": unsplash("1615663245857-ac93bb7c39e7"),
    "ms-003": unsplash("1613141411244-0e4ac259d217"),
    "ms-004": unsplash("1563297007-0686b7003af7"),
    # Headphones
    "hp-001": unsplash("1583394838336-acd977736f90"),
    "hp-002": unsplash("1484704849700-f032a568e944"),
    "hp-003": unsplash("1546435770-a3e426bf472b"),
    "hp-004": unsplash("1618366712010-f4ae9c647dcb"),
    "hp-005": unsplash("1505740420928-5e560c06d30e"),
    "hp-006": unsplash("1572569511254-d8f925fe2cbb"),
    "hp-007": unsplash("1590658268037-6bf12165a8df"),
    # Laptop stands
    "ls-001": wikimedia("4/43/Laptop_stand.jpg"),
    "ls-002": unsplash("1593642632559-0c6d3fc62b89"),
    "ls-003": wikimedia("4/43/Trust_Notebook_Cooling_Stand_Xtreme.jpg"),
    "ls-004": wikimedia("1/14/Laptop_cooler.JPG"),
    "ls-005": wikimedia("8/8f/Chlad%C3%ADc%C3%AD_podlo%C5%BEka_pod_notebook.jpg"),
    # Monitors
    "mn-001": unsplash("1585792180666-f7347c490ee2"),
    "mn-002": unsplash("1527443224154-c4a3942d3acf"),
    "mn-003": unsplash("1625842268584-8f3296236761"),
    "mn-004": unsplash("1547082299-de196ea013d6"),
    "mn-005": unsplash("1593640408182-31c70c8268f5"),
    # Webcams
    "wc-001": unsplash("1629429407756-446d66f5b24e"),
    "wc-002": unsplash("1623949556303-b0d17d198863"),
    "wc-003": wikimedia("f/f5/Fancy_webcam_on_computer_screen.jpg"),
    "wc-004": wikimedia("7/79/Webcam_%28Logitech_c922%29.jpg"),
    # Desk accessories
    "da-001": wikimedia("f/fa/A_desk_lamp.jpg"),
    "da-002": wikimedia("2/24/Monitor_arm_stand_%281%29.jpg"),
    "da-003": unsplash("1596207891316-23851be3cc20"),
    "da-004": unsplash("1602526432604-029a709e131c"),
    "da-005": unsplash("1587826080692-f439cd0b70da"),
}


def square(img: Image.Image) -> Image.Image:
    img = img.convert("RGB")
    w, h = img.size
    side = min(w, h)
    left, top = (w - side) // 2, (h - side) // 2
    return img.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.LANCZOS)


def fetch(client: httpx.Client, url: str) -> bytes:
    for attempt in range(5):
        r = client.get(url)
        if r.status_code == 429:
            time.sleep(5 * (attempt + 1))
            continue
        r.raise_for_status()
        return r.content
    raise RuntimeError(f"rate limited: {url}")


def main(force: bool = False) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with httpx.Client(headers=UA, follow_redirects=True, timeout=30) as client:
        for pid, url in SOURCES.items():
            out = OUT_DIR / f"{pid}.jpg"
            if out.exists() and not force:
                continue
            square(Image.open(io.BytesIO(fetch(client, url)))).save(out, "JPEG", quality=80, optimize=True, progressive=True)
            print(f"{pid}: {out.stat().st_size // 1024} KB")
            time.sleep(1)


if __name__ == "__main__":
    main(force="--force" in sys.argv)
