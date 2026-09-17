package com.exchange.matching.mock.repository;

import com.exchange.matching.protocol.model.TradingPair;
import com.exchange.matching.mock.mapper.TradingPairMapper;
import com.exchange.matching.mock.entity.TradingPairEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.math.BigDecimal;
import java.util.List;

public class TradingPairRepository {
    private final TradingPairMapper mapper;
    public TradingPairRepository(TradingPairMapper mapper) { this.mapper = mapper; }
    public void initialize() {
        int position = 0;
        for (var pair : TradingPair.CATALOG) {
            var row = new TradingPairEntity();
            row.symbol = pair.symbol(); row.baseAsset = pair.baseAsset(); row.quoteAsset = pair.quoteAsset();
            row.priceScale = pair.priceScale(); row.quantityScale = pair.quantityScale();
            row.examplePrice = pair.examplePrice(); row.sortOrder = position++;
            if (mapper.selectById(row.symbol) == null) {
                try { mapper.insert(row); }
                catch (org.springframework.dao.DuplicateKeyException concurrentInitialization) { /* Keep saved configuration. */ }
            }
        }
    }
    public List<TradingPair> read() {
        return mapper.selectList(new QueryWrapper<TradingPairEntity>().eq("deleted", false).orderByAsc("sort_order", "symbol")).stream().map(row -> {
            var pair = new TradingPair(row.symbol, row.baseAsset, row.quoteAsset,
                    row.priceScale, row.quantityScale, row.examplePrice);
            if (pair.priceScale() < 0 || pair.priceScale() > 8 || pair.quantityScale() < 0 || pair.quantityScale() > 8)
                throw new IllegalStateException("交易对精度必须为 0–8：" + pair.symbol());
            return pair;
        }).toList();
    }
    public void update(String symbol, String examplePrice) {
        update(symbol, examplePrice, null, null);
    }
    public synchronized void update(String symbol, String examplePrice, Integer priceScale, Integer quantityScale) {
        var pair = require(symbol, false);
        int priceDigits = priceScale == null ? pair.priceScale() : priceScale;
        int quantityDigits = quantityScale == null ? pair.quantityScale() : quantityScale;
        if (priceDigits < 0 || priceDigits > 8 || quantityDigits < 0 || quantityDigits > 8)
            throw new IllegalArgumentException("价格和数量小数位必须为 0–8 的整数");
        if ((priceDigits != pair.priceScale() || quantityDigits != pair.quantityScale()) && mapper.countHistory(symbol) > 0)
            throw new IllegalArgumentException("该交易对已有订单或撮合记录，不能直接改变计价精度；请使用新的测试交易对");
        try {
            if (examplePrice == null || examplePrice.length() > 64) throw new IllegalArgumentException("请输入示例价格");
            var price = new BigDecimal(examplePrice).setScale(priceDigits, java.math.RoundingMode.UNNECESSARY);
            if (price.signum() <= 0 || price.movePointRight(priceDigits).longValueExact() <= 0)
                throw new IllegalArgumentException("示例价格必须大于零");
            if (mapper.update(null, new UpdateWrapper<TradingPairEntity>().eq("symbol", symbol)
                    .set("price_scale", priceDigits).set("quantity_scale", quantityDigits)
                    .set("example_price", price.toPlainString()).setSql("updated_at=CURRENT_TIMESTAMP(6)")) != 1)
                throw new IllegalArgumentException("交易对不存在");
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("示例价格超出精度或整数范围");
        }
    }

    public TradingPair require(String symbol, boolean includeDeleted) {
        var row = mapper.selectById(symbol);
        if (row == null || (!includeDeleted && Boolean.TRUE.equals(row.deleted))) throw new IllegalArgumentException("交易对不存在或已删除");
        return new TradingPair(row.symbol, row.baseAsset, row.quoteAsset, row.priceScale, row.quantityScale, row.examplePrice);
    }

    public void add(TradingPair pair) {
        if (pair.baseAsset() == null || !pair.baseAsset().matches("[A-Z0-9]{1,16}")
                || !"USDT".equals(pair.quoteAsset()) || "USDT".equals(pair.baseAsset())
                || !(pair.baseAsset() + "_USDT").equals(pair.symbol())
                || pair.priceScale() < 0 || pair.priceScale() > 8 || pair.quantityScale() < 0 || pair.quantityScale() > 8)
            throw new IllegalArgumentException("请输入有效的 USDT 交易对，价格和数量精度为 0–8");
        String price;
        try {
            if (pair.examplePrice() == null || pair.examplePrice().length() > 64) throw new IllegalArgumentException("请输入示例价格");
            var decimal = new BigDecimal(pair.examplePrice()).setScale(pair.priceScale(), java.math.RoundingMode.UNNECESSARY);
            if (decimal.signum() <= 0 || decimal.movePointRight(pair.priceScale()).longValueExact() <= 0) throw new ArithmeticException();
            price = decimal.toPlainString();
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException("示例价格超出精度或范围"); }
        var previous = mapper.selectById(pair.symbol());
        if (previous != null) {
            if (!Boolean.TRUE.equals(previous.deleted)) throw new IllegalArgumentException("交易对已存在");
            if (previous.priceScale != pair.priceScale() || previous.quantityScale != pair.quantityScale())
                throw new IllegalArgumentException("重新添加时必须保留原精度");
            mapper.update(null, new UpdateWrapper<TradingPairEntity>().eq("symbol", pair.symbol()).set("deleted", false).set("example_price", price));
            return;
        }
        var row = new TradingPairEntity();
        row.symbol = pair.symbol(); row.baseAsset = pair.baseAsset(); row.quoteAsset = pair.quoteAsset();
        row.priceScale = pair.priceScale(); row.quantityScale = pair.quantityScale(); row.examplePrice = price; row.sortOrder = 1000;
        try { mapper.insert(row); } catch (org.springframework.dao.DuplicateKeyException duplicate) { throw new IllegalArgumentException("交易对已存在"); }
    }

    public void delete(String symbol) {
        require(symbol, true);
        mapper.update(null, new UpdateWrapper<TradingPairEntity>().eq("symbol", symbol).set("deleted", true));
    }
}
