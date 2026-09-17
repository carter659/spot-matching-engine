package com.exchange.matching.mock.repository;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
/** Migrate the legacy symbol-only unique key without deleting strategy data. */
public final class TradeStrategySchema {
 private TradeStrategySchema() {}
 public static void migrate(DataSource source) {
  var jdbc=new JdbcTemplate(source);
  jdbc.update("UPDATE mock_trade_strategy SET mode='FOLLOW' WHERE mode IS NULL");
  try(var connection=source.getConnection()) {
   if(!connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL"))return;
   var keys=jdbc.queryForList("SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mock_trade_strategy' AND NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY' GROUP BY INDEX_NAME HAVING COUNT(*)=1 AND MAX(COLUMN_NAME)='symbol'",String.class);
   for(String key:keys)jdbc.execute("ALTER TABLE mock_trade_strategy DROP INDEX `"+key.replace("`","``")+"`");
  }catch(java.sql.SQLException e){throw new IllegalStateException("策略索引迁移失败",e);}
 }
}
