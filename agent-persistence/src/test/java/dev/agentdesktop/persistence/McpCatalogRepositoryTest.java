package dev.agentdesktop.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCatalogRepositoryTest {
    @Test void restoresServerAndCatalog(@TempDir Path home){
        LocalDatabase db=new LocalDatabase(home);db.migrate();var repo=new McpCatalogRepository(db,new ObjectMapper());String now=Instant.now().toString();
        repo.save(new McpCatalogRepository.ServerRow("mcp_test1234","fixture","STREAMABLE_HTTP","CONFIGURED",true,"https://example.com/mcp",null,List.of(),null,List.of(),"credential_ref","{\"maxAttempts\":3}",null,null,null,0,now,now));
        repo.replaceTools("mcp_test1234",1,List.of(new McpCatalogRepository.ToolRow("mcp:mcp_test1234:echo","mcp_test1234","echo","echo","{\"type\":\"object\"}","DEMO_SAFE",true,1,now,now)));
        assertThat(repo.get("mcp_test1234").credentialRef()).isEqualTo("credential_ref");
        assertThat(repo.listTools("mcp_test1234")).extracting(McpCatalogRepository.ToolRow::name).containsExactly("echo");
    }

    @Test void softDeleteHidesServerDisablesCatalogAndAllowsNameReuse(@TempDir Path home) throws Exception {
        LocalDatabase db=new LocalDatabase(home);db.migrate();var repo=new McpCatalogRepository(db,new ObjectMapper());String now=Instant.now().toString();
        repo.save(server("mcp_deleted1","reusable-name",now));
        assertThat(repo.hasServerNamedIncludingDeleted("reusable-name")).isTrue();
        repo.replaceTools("mcp_deleted1",1,List.of(new McpCatalogRepository.ToolRow("mcp:mcp_deleted1:echo","mcp_deleted1","echo","echo","{\"type\":\"object\"}","DEMO_SAFE",true,1,now,now)));

        McpCatalogRepository.ServerRow deleted=repo.softDelete("mcp_deleted1");

        assertThat(deleted.credentialRef()).isEqualTo("credential_ref");
        assertThat(repo.list(50)).isEmpty();
        assertThat(repo.hasServerNamedIncludingDeleted("reusable-name")).isTrue();
        assertThat(repo.listTools("mcp_deleted1")).isEmpty();
        assertThatThrownBy(()->repo.get("mcp_deleted1")).isInstanceOf(McpCatalogRepository.DomainException.class)
                .hasMessage("MCP_SERVER_NOT_FOUND");
        try(var connection=db.open();var statement=connection.prepareStatement(
                "SELECT s.status,s.enabled,s.credential_ref,s.deleted_at,t.enabled tool_enabled FROM mcp_server s JOIN mcp_tool t ON t.server_id=s.id WHERE s.id=?")){
            statement.setString(1,"mcp_deleted1");
            try(var result=statement.executeQuery()){
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("DELETED");
                assertThat(result.getInt("enabled")).isZero();
                assertThat(result.getString("credential_ref")).isNull();
                assertThat(result.getString("deleted_at")).isNotBlank();
                assertThat(result.getInt("tool_enabled")).isZero();
            }
        }

        repo.save(server("mcp_reused12","reusable-name",now));
        assertThat(repo.list(50)).extracting(McpCatalogRepository.ServerRow::id).containsExactly("mcp_reused12");
    }

    private static McpCatalogRepository.ServerRow server(String id,String name,String now){
        return new McpCatalogRepository.ServerRow(id,name,"STREAMABLE_HTTP","CONFIGURED",true,"https://example.com/mcp",null,List.of(),null,List.of(),"credential_ref","{\"maxAttempts\":3}",null,null,null,0,now,now);
    }
}
