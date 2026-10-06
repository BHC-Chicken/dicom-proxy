package dev.ioexception.dicom.common.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KnowledgeCategory {
    WIKI("confluence_wiki"),
    BLOG("tech_blog");

    private final String indexName;

    @JsonValue
    public String getValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static KnowledgeCategory from(String value) {
        if (value == null || value.isBlank()) {
            return WIKI;
        }
        for (KnowledgeCategory category : values()) {
            if (category.name().equalsIgnoreCase(value) || category.getIndexName().equalsIgnoreCase(value)) {
                return category;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 지식 카테고리입니다: " + value);
    }
}
