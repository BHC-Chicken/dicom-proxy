package dev.ioexception.dicom.common.config;

import dev.ioexception.dicom.common.type.KnowledgeCategory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToKnowledgeCategoryConverter());
    }

    private static class StringToKnowledgeCategoryConverter implements Converter<String, KnowledgeCategory> {
        @Override
        public KnowledgeCategory convert(String source) {
            return KnowledgeCategory.from(source);
        }
    }
}
