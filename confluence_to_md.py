import os
import sys
import argparse
import quopri
from bs4 import BeautifulSoup
import markdownify

def decode_quoted_printable(raw_bytes: bytes) -> str:
    """Quoted-Printable (=EA=B0... 형태) 바이너리를 UTF-8 한글 텍스트로 디코딩"""
    decoded_bytes = quopri.decodestring(raw_bytes)
    # cp949나 utf-8 중 호환되는 디코더 적용
    try:
        return decoded_bytes.decode("utf-8")
    except UnicodeDecodeError:
        return decoded_bytes.decode("cp949", errors="ignore")

def clean_confluence_html(soup: BeautifulSoup) -> BeautifulSoup:
    """Confluence HTML에서 불필요한 레이아웃, 메타데이터, 노이즈 제거"""
    for tag in soup(["style", "script", "noscript", "meta"]):
        tag.decompose()

    for noise in soup.find_all(class_=["toc", "pagebreak", "noprint"]):
        noise.decompose()

    return soup

def convert_doc_to_markdown(input_path: str, output_path: str = None) -> str:
    """Confluence Export Word(.doc) 파일의 Quoted-Printable 인코딩을 해제하고 Markdown으로 변환"""
    input_path = input_path.strip(' "\'')

    if not os.path.exists(input_path):
        raise FileNotFoundError(f"입력 파일을 찾을 수 없습니다: {input_path}")

    print(f"\n📄 파일 읽기 및 디코딩 중: {input_path}")

    # 1. 바이너리 모드로 읽은 뒤 Quoted-Printable 한글 디코딩 수행
    with open(input_path, "rb") as f:
        raw_bytes = f.read()

    html_content = decode_quoted_printable(raw_bytes)

    # 2. BeautifulSoup 파싱
    soup = BeautifulSoup(html_content, "html.parser")
    soup = clean_confluence_html(soup)

    # Confluence 본문 영역 추출
    main_content = (
        soup.find("div", class_="Section1")
        or soup.find("div", id="main-content")
        or soup.find("body")
        or soup
    )

    # 3. HTML -> Markdown 변환 (표, 헤더, 리스트 보존)
    md_text = markdownify.markdownify(
        str(main_content),
        heading_style="ATX",       # #, ## 형태의 Markdown 헤더
        code_language="",
        strip=['script', 'style']
    )

    # 4. 연속된 공백 줄 정리
    lines = md_text.splitlines()
    cleaned_lines = []
    prev_empty = False
    for line in lines:
        is_empty = not line.strip()
        if is_empty and prev_empty:
            continue
        cleaned_lines.append(line)
        prev_empty = is_empty

    final_md = "\n".join(cleaned_lines).strip()

    # 저장 경로 설정
    if not output_path:
        base_name = os.path.splitext(input_path)[0]
        output_path = f"{base_name}.md"
    else:
        output_path = output_path.strip(' "\'')

    with open(output_path, "w", encoding="utf-8") as f:
        f.write(final_md)

    print(f"✅ 변환 성공! 한글 복원 완료된 Markdown 파일: {output_path}\n")
    return output_path

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Confluence Export Word(.doc) -> Markdown 변환기 (한글 복원 포함)")
    parser.add_argument("-i", "--input", help="변환할 Confluence .doc 파일 경로", type=str)
    parser.add_argument("-o", "--output", help="저장할 .md 파일 경로", type=str)

    args = parser.parse_args()

    input_file = args.input
    output_file = args.output

    if not input_file:
        print("=" * 50)
        print("  Confluence .doc -> Markdown 변환기 (한글 디코딩 지원)")
        print("=" * 50)
        input_file = input("📌 변환할 Confluence .doc 파일 경로를 입력하세요: ")

    if not input_file.strip():
        print("❌ 파일 경로가 입력되지 않았습니다.")
        sys.exit(1)

    try:
        convert_doc_to_markdown(input_file, output_file)
    except Exception as e:
        print(f"❌ 오류 발생: {e}")
