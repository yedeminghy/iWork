package dev.agentdesktop.host.api;

import dev.agentdesktop.host.DiagnosticService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class DiagnosticController {
    private final DiagnosticService diagnostics;
    public DiagnosticController(DiagnosticService diagnostics){this.diagnostics=diagnostics;}

    @GetMapping("/api/v1/diagnostics/preview")
    public DiagnosticService.DiagnosticPreview preview(){return diagnostics.preview();}
}
