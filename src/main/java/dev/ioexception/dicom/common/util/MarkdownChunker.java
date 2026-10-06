package dev.ioexception.dicom.common.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

public class MarkdownChunker {

    private static final int DEFAULT_MAX_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 150; // 문맥 보존용 오버랩 글자 수

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChunkItem {
        private int chunkIndex;
        private String section;          // 현재 최하위 섹션명
        private String headerPath;       // 계층적 헤더 전체 경로 (예: 페이지 > H1 > H2)
        private String rawContent;       // 청크 원문 (Overlap 포함)
        private String contextualContent; // 임베딩/LLM 문맥 주입용 텍스트
    }

    public static List<ChunkItem> splitMarkdown(String docTitle, String markdownContent) {
        return splitMarkdown(docTitle, markdownContent, DEFAULT_MAX_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    public static List<ChunkItem> splitMarkdown(String docTitle, String markdownContent, int maxChunkSize, int chunkOverlap) {
        List<ChunkItem> chunks = new ArrayList<>();

        if (markdownContent == null || markdownContent.isBlank()) {
            return chunks;
        }

        String[] lines = markdownContent.split("\\r?\\n");
        
        // 계층적 헤더 추적용 배열 (h1~h6)
        String[] headerStack = new String[7];
        for (int i = 0; i <= 6; i++) {
            headerStack[i] = "";
        }

        StringBuilder currentSectionBuilder = new StringBuilder();
        int chunkIndex = 0;
        String previousOverlapText = ""; // 이전 청크의 오버랩 텍스트

        for (String line : lines) {
            String trimmed = line.trim();

            // 1. 헤더(#, ##, ### 등)를 만난 경우 섹션 단위 분할
            if (trimmed.startsWith("#")) {
                int level = getHeaderLevel(trimmed);
                String headerText = trimmed.replaceAll("^#+\\s*", "");

                // 이전 섹션 내용이 존재하면 청크 처리
                if (currentSectionBuilder.length() > 0) {
                    chunkIndex = processSectionText(
                            docTitle,
                            headerStack,
                            currentSectionBuilder.toString().trim(),
                            previousOverlapText,
                            chunks,
                            chunkIndex,
                            maxChunkSize,
                            chunkOverlap
                    );

                    currentSectionBuilder.setLength(0);
                    previousOverlapText = ""; // 섹션 변경 시 오버랩 리셋
                }

                // 헤더 스택 업데이트
                headerStack[level] = headerText;
                for (int i = level + 1; i <= 6; i++) {
                    headerStack[i] = "";
                }

                currentSectionBuilder.append(line).append("\n");
            } else {
                currentSectionBuilder.append(line).append("\n");
            }
        }

        // 마지막 섹션 처리
        if (currentSectionBuilder.length() > 0) {
            processSectionText(
                    docTitle,
                    headerStack,
                    currentSectionBuilder.toString().trim(),
                    previousOverlapText,
                    chunks,
                    chunkIndex,
                    maxChunkSize,
                    chunkOverlap
            );
        }

        return chunks;
    }

    /**
     * 하나의 섹션 텍스트를 문장/단락 경계선에 맞춰 Overlap을 적용하여 청크로 분할
     */
    private static int processSectionText(
            String docTitle,
            String[] headerStack,
            String sectionText,
            String initialOverlap,
            List<ChunkItem> chunks,
            int currentChunkIndex,
            int maxChunkSize,
            int chunkOverlap
    ) {
        if (sectionText.isBlank()) {
            return currentChunkIndex;
        }

        String currentSection = getCurrentSection(headerStack);
        String headerPath = buildHeaderPath(docTitle, headerStack);

        // 섹션 텍스트 전체 길이가 maxChunkSize 이하라면 한 번에 저장
        if (sectionText.length() <= maxChunkSize) {
            currentChunkIndex++;
            String fullContent = initialOverlap.isBlank() ? sectionText : initialOverlap + "\n" + sectionText;
            String contextual = buildContextualContent(headerPath, fullContent);

            chunks.add(ChunkItem.builder()
                    .chunkIndex(currentChunkIndex)
                    .section(currentSection)
                    .headerPath(headerPath)
                    .rawContent(fullContent)
                    .contextualContent(contextual)
                    .build());

            return currentChunkIndex;
        }

        // maxChunkSize를 초과하는 긴 섹션 텍스트는 문장/단락 경계선 기반 슬라이딩 윈도우 적용
        int startPos = 0;
        String overlapText = initialOverlap;

        while (startPos < sectionText.length()) {
            int endPos = Math.min(startPos + maxChunkSize, sectionText.length());

            // 800자 지점이 문장 중간이라면 마침표/줄바꿈 경계선 탐색 (자연스러운 문장 마감)
            if (endPos < sectionText.length()) {
                int boundary = findSentenceBoundary(sectionText, startPos, endPos);
                if (boundary > startPos) {
                    endPos = boundary;
                }
            }

            String chunkRawText = sectionText.substring(startPos, endPos).trim();
            if (!chunkRawText.isBlank()) {
                currentChunkIndex++;

                // 이전 청크의 오버랩 텍스트 결합
                String fullContent = overlapText.isBlank() ? chunkRawText : overlapText + "\n" + chunkRawText;
                String contextual = buildContextualContent(headerPath, fullContent);

                chunks.add(ChunkItem.builder()
                        .chunkIndex(currentChunkIndex)
                        .section(currentSection)
                        .headerPath(headerPath)
                        .rawContent(fullContent)
                        .contextualContent(contextual)
                        .build());

                // 다음 청크를 위한 Chunk Overlap 텍스트 추출 (현재 청크의 뒤쪽 N자)
                if (chunkRawText.length() > chunkOverlap) {
                    overlapText = "..." + chunkRawText.substring(chunkRawText.length() - chunkOverlap);
                } else {
                    overlapText = chunkRawText;
                }
            }

            startPos = endPos;
        }

        return currentChunkIndex;
    }

    /**
     * 문장 마감 경계선 탐색 (마침표 ., 줄바꿈 \n, 물음표/느낌표 등)
     */
    private static int findSentenceBoundary(String text, int startPos, int endPos) {
        // endPos 기준으로 역방향 검색
        for (int i = endPos; i > startPos + (endPos - startPos) / 2; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '.' || c == '?' || c == '!') {
                return i;
            }
        }
        return endPos; // 적절한 마침표를 못 찾으면 원래 endPos 유지
    }

    private static int getHeaderLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return Math.min(count, 6);
    }

    private static String getCurrentSection(String[] headerStack) {
        for (int i = 6; i >= 1; i--) {
            if (!headerStack[i].isBlank()) {
                return headerStack[i];
            }
        }
        return "개요";
    }

    private static String buildHeaderPath(String docTitle, String[] headerStack) {
        List<String> pathList = new ArrayList<>();
        if (docTitle != null && !docTitle.isBlank()) {
            pathList.add(docTitle);
        }

        for (int i = 1; i <= 6; i++) {
            if (!headerStack[i].isBlank()) {
                pathList.add(headerStack[i]);
            }
        }

        if (pathList.isEmpty()) {
            return "개요";
        }
        return String.join(" > ", pathList);
    }

    private static String buildContextualContent(String headerPath, String rawContent) {
        String header = String.format("[문서 위치: %s]\n", headerPath);
        return header + rawContent;
    }
}
