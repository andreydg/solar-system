package dev.andreydg.solarsystem.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardingController {

    // Forward client-side routes to the SPA shell, but never paths that name a real file
    // (anything with a dot, e.g. /favicon.ico, /favicon-32.png) — those must fall through to
    // the static resource handler. api/assets/actuator/textures are served by their own handlers.
    @GetMapping(value = "/{path:^(?!api|assets|actuator|textures)[^.]*$}/**")
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
