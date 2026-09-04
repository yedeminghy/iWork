package dev.agentdesktop.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResultSanitizerTest {
    @Test void redactsSecretsAndBoundsVisibleResults(){
        ResultSanitizer sanitizer=new ResultSanitizer(new ObjectMapper());
        String result=sanitizer.summary("Authorization: Bearer secret-value "+"x".repeat(20_000));
        assertThat(result).contains("[REDACTED]").contains("已截断").doesNotContain("secret-value");
    }
}
