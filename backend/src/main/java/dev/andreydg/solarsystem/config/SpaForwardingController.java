package dev.andreydg.solarsystem.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardingController {

    @GetMapping(value = "/{path:^(?!api|assets|actuator|index\\.html).*$}/**")
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
