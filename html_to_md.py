import trafilatura

# 1. 공식 문서 URL에서 HTML 수신
url = "https://www.elastic.co/search-labs/blog/lucene-bringing-maximum-inner-product-to-lucene"
downloaded = trafilatura.fetch_url(url)

# 2. 메타데이터에서 문서 메인 제목(Title) 추출
metadata = trafilatura.extract_metadata(downloaded)
doc_title = metadata.title if metadata and metadata.title else ""

# 3. 사이드바/메뉴가 정제된 본문 추출 (포맷팅 및 리콜 보존 옵션 적용)
clean_text = trafilatura.extract(
    downloaded, 
    output_format="markdown",
    include_formatting=True, # #, ## 등 마크다운 헤더 포맷 보존
    include_links=True,       # 링크 유지
    include_images=False,     # 이미지 제외
    favor_recall=True         # 제목 및 본문 요소 탈락 최소화
)

# 4. 최상단 H1 큰 제목(# Title) 자동 보정 후 파일 저장
output_file = "lucene-bringing-maximum-inner-product-to-lucene.md"
if clean_text:
    final_markdown = clean_text
    # 맨 위에 # 큰 제목이 없는 경우 메타데이터 제목으로 # H1 제목 추가
    if doc_title and not clean_text.strip().startswith("#"):
        final_markdown = f"# {doc_title}\n\n" + clean_text

    with open(output_file, "w", encoding="utf-8") as f:
        f.write(final_markdown)
    print(f"✅ 성공적으로 마크다운 파일이 생성되었습니다: {output_file}")
else:
    print("❌ 문서 본문을 추출하지 못했습니다.")
