package com.exchange.matching.mock.controller;
import com.exchange.matching.mock.service.ScenarioService;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController @Profile("database & messaging") @RequestMapping("/mock/scenarios")
public class ScenarioController {
 private final ScenarioService service;
 public ScenarioController(ScenarioService service){this.service=service;}
 public record Start(String symbol,String scenario,boolean isolated){}
 @GetMapping public Object list(){return service.list();}
 @PostMapping public Object start(@RequestBody Start request){return service.start(request.symbol(),request.scenario(),request.isolated());}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
}
