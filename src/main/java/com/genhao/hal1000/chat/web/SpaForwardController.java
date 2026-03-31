package com.genhao.hal1000.chat.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serve the Vue3 SPA (built into src/main/resources/static).
 * Any non-API route should return index.html so client-side router can handle it.
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {"/", "/chat", "/conversations"})
    public String index() {
        return "forward:/index.html";
    }
}

