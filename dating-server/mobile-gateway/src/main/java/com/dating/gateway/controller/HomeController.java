package com.dating.gateway.controller;

import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.HomeService;
import com.dating.gateway.vo.HomeCardVO;
import com.dating.gateway.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/** Home Controller. */
@RestController
@RequestMapping("/api/v1/home")
@Tag(name = "Home", description = "Home page endpoints")
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping("/cards")
    @Operation(summary = "Get home cards")
    public Result<java.util.List<HomeCardVO>> getHomeCards(@RequestParam(defaultValue = "5") int pageSize) {
        var ctx = RequestContext.current();
        return Result.ok(homeService.getHomeCards(ctx.getUserId(), pageSize));
    }
}
