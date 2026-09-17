package com.exchange.matching.mock.controller;
import com.exchange.matching.mock.dto.TradeStrategyRequest;
import com.exchange.matching.mock.service.TradeStrategyService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController @Profile("database & messaging") @RequestMapping("/mock/trade-strategies")
public class TradeStrategyController {
 private final TradeStrategyService service;public TradeStrategyController(TradeStrategyService service){this.service=service;}
 @GetMapping public List<Map<String,Object>> list(){return service.list();}
 @PostMapping public Map<String,String> add(@RequestBody TradeStrategyRequest request){service.add(request);return Map.of("status","added");}
 @PutMapping("/{id}") public Map<String,String> update(@PathVariable String id,@RequestBody TradeStrategyRequest request){service.update(id,request);return Map.of("status","updated");}
 @PostMapping("/{id}/start") public Map<String,String> start(@PathVariable String id){service.running(id,true);return Map.of("status","running");}
 @PostMapping("/{id}/pause") public Map<String,String> pause(@PathVariable String id){service.running(id,false);return Map.of("status","paused");}
 @DeleteMapping("/{id}") public Map<String,String> delete(@PathVariable String id){service.delete(id);return Map.of("status","deleted");}
 @GetMapping("/{id}/records") public Map<String,Object> records(@PathVariable String id,@RequestParam(defaultValue="1")int page){return service.records(id,page);}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> invalid(IllegalArgumentException e){return Map.of("error",e.getMessage());}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) public Map<String,String> database(){return Map.of("error","数据库暂不可用，请重新读取确认状态");}
}
