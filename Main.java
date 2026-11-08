import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

public class Main {

    enum Side { BUY, SELL }

    enum OrderType { LIMIT, MARKET }

    enum Status { NEW, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }

    static final class Money {
        private Money() { }

        static long parse(String text) {
            try {
                return new BigDecimal(text.trim()).movePointRight(2).longValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                throw new IllegalArgumentException("Invalid price: " + text + " (max 2 decimals)");
            }
        }

        static String format(long cents) {
            String sign = cents < 0 ? "-" : "";
            long abs = Math.abs(cents);
            return String.format("%s%d.%02d", sign, abs / 100, abs % 100);
        }
    }

    static final class Order {
        final long id;
        final String trader;
        final String symbol;
        final Side side;
        final OrderType type;
        final long price;
        final long quantity;
        long remaining;
        Status status = Status.NEW;
        String message = "";

        Order(long id, String trader, String symbol, Side side, OrderType type, long price, long quantity) {
            this.id = id;
            this.trader = trader;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
            this.price = price;
            this.quantity = quantity;
            this.remaining = quantity;
        }

        long filled() {
            return quantity - remaining;
        }

        @Override
        public String toString() {
            String priceText = type == OrderType.MARKET ? "MKT" : Money.format(price);
            return String.format("#%d %-6s %-4s %-6s %d @ %s [%s, filled=%d]",
                    id, trader, side, type, quantity, priceText, status, filled());
        }
    }

    static final class Trade {
        final long id;
        final String symbol;
        final long price;
        final long quantity;
        final String buyer;
        final String seller;

        Trade(long id, String symbol, long price, long quantity, String buyer, String seller) {
            this.id = id;
            this.symbol = symbol;
            this.price = price;
            this.quantity = quantity;
            this.buyer = buyer;
            this.seller = seller;
        }

        long value() {
            return price * quantity;
        }

        @Override
        public String toString() {
            return String.format("T%-3d %-5s %5d @ %-9s %s <- %s",
                    id, symbol, quantity, Money.format(price), buyer, seller);
        }
    }

    static final class Account {
        final String name;
        long cash;
        final Map<String, Long> positions = new LinkedHashMap<>();

        Account(String name, long cash, List<String> symbols, long startingShares) {
            this.name = name;
            this.cash = cash;
            for (String s : symbols) {
                positions.put(s, startingShares);
            }
        }

        long position(String symbol) {
            return positions.getOrDefault(symbol, 0L);
        }

        void addShares(String symbol, long delta) {
            positions.merge(symbol, delta, Long::sum);
        }
    }

    static final class OrderBook {
        final String symbol;

        private final TreeMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
        private final TreeMap<Long, Deque<Order>> asks = new TreeMap<>();
        private final Map<Long, Order> resting = new HashMap<>();

        private long lastPrice;
        private long volume;
        private long high = Long.MIN_VALUE;
        private long low = Long.MAX_VALUE;

        OrderBook(String symbol, long referencePrice) {
            this.symbol = symbol;
            this.lastPrice = referencePrice;
        }

        long lastPrice() { return lastPrice; }
        long volume() { return volume; }
        long high() { return high == Long.MIN_VALUE ? lastPrice : high; }
        long low() { return low == Long.MAX_VALUE ? lastPrice : low; }

        Long bestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
        Long bestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }

        List<Trade> match(Order incoming, TradeIdGenerator ids) {
            List<Trade> trades = new ArrayList<>();
            TreeMap<Long, Deque<Order>> opposite = incoming.side == Side.BUY ? asks : bids;

            while (incoming.remaining > 0 && !opposite.isEmpty()) {
                Map.Entry<Long, Deque<Order>> best = opposite.firstEntry();
                long bestPrice = best.getKey();

                if (incoming.type == OrderType.LIMIT && !crosses(incoming, bestPrice)) {
                    break;
                }

                Deque<Order> queue = best.getValue();
                Order maker = queue.peekFirst();
                long qty = Math.min(incoming.remaining, maker.remaining);

                String buyer = incoming.side == Side.BUY ? incoming.trader : maker.trader;
                String seller = incoming.side == Side.SELL ? incoming.trader : maker.trader;
                Trade trade = new Trade(ids.next(), symbol, bestPrice, qty, buyer, seller);
                trades.add(trade);

                incoming.remaining -= qty;
                maker.remaining -= qty;
                updateStats(bestPrice, qty);

                if (maker.remaining == 0) {
                    maker.status = Status.FILLED;
                    queue.pollFirst();
                    resting.remove(maker.id);
                } else {
                    maker.status = Status.PARTIALLY_FILLED;
                }
                if (queue.isEmpty()) {
                    opposite.pollFirstEntry();
                }
            }

            finish(incoming);
            return trades;
        }

        private boolean crosses(Order incoming, long bookPrice) {
            return incoming.side == Side.BUY ? incoming.price >= bookPrice : incoming.price <= bookPrice;
        }

        private void finish(Order incoming) {
            if (incoming.remaining == 0) {
                incoming.status = Status.FILLED;
            } else if (incoming.type == OrderType.LIMIT) {

                incoming.status = incoming.filled() > 0 ? Status.PARTIALLY_FILLED : Status.NEW;
                TreeMap<Long, Deque<Order>> own = incoming.side == Side.BUY ? bids : asks;
                own.computeIfAbsent(incoming.price, p -> new ArrayDeque<>()).addLast(incoming);
                resting.put(incoming.id, incoming);
            } else {

                incoming.status = incoming.filled() > 0 ? Status.PARTIALLY_FILLED : Status.CANCELLED;
                incoming.message = "no more liquidity, remainder cancelled";
            }
        }

        private void updateStats(long price, long qty) {
            lastPrice = price;
            volume += qty;
            high = Math.max(high, price);
            low = Math.min(low, price);
        }

        String cancel(long orderId, String trader) {
            Order order = resting.get(orderId);
            if (order == null) {
                return "Order not found (maybe already filled or cancelled)";
            }
            if (!order.trader.equals(trader)) {
                return "Rejected: order belongs to another trader";
            }
            TreeMap<Long, Deque<Order>> side = order.side == Side.BUY ? bids : asks;
            Deque<Order> queue = side.get(order.price);
            queue.remove(order);
            if (queue.isEmpty()) {
                side.remove(order.price);
            }
            resting.remove(orderId);
            order.status = Status.CANCELLED;
            return "Cancelled: " + order;
        }

        long availableAtBest(Side incomingSide) {
            TreeMap<Long, Deque<Order>> opposite = incomingSide == Side.BUY ? asks : bids;
            if (opposite.isEmpty()) {
                return 0;
            }
            return opposite.firstEntry().getValue().stream().mapToLong(o -> o.remaining).sum();
        }

        String format(int levels) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("---- %s order book (last %s) ----%n", symbol, Money.format(lastPrice)));
            sb.append(String.format("  %-10s %10s%n", "ASK", "QTY"));

            List<Map.Entry<Long, Deque<Order>>> askLevels = new ArrayList<>(asks.entrySet());
            int from = Math.min(levels, askLevels.size());
            for (int i = from - 1; i >= 0; i--) {
                Map.Entry<Long, Deque<Order>> e = askLevels.get(i);
                sb.append(String.format("  %-10s %10d%n", Money.format(e.getKey()), totalQty(e.getValue())));
            }
            sb.append("  ----------------------\n");
            int count = 0;
            for (Map.Entry<Long, Deque<Order>> e : bids.entrySet()) {
                if (count++ >= levels) {
                    break;
                }
                sb.append(String.format("  %-10s %10d%n", Money.format(e.getKey()), totalQty(e.getValue())));
            }
            sb.append(String.format("  %-10s %10s%n", "BID", "QTY"));
            return sb.toString();
        }

        private long totalQty(Deque<Order> queue) {
            long total = 0;
            for (Order o : queue) {
                total += o.remaining;
            }
            return total;
        }
    }

    static final class TradeIdGenerator {
        private long current;

        long next() {
            return ++current;
        }
    }

    static final class Exchange {
        static final long STARTING_CASH = Money.parse("100000.00");
        static final long STARTING_SHARES = 500;
        static final long MAX_QUANTITY = 10_000;

        private final Map<String, OrderBook> books = new LinkedHashMap<>();
        private final Map<String, Account> accounts = new LinkedHashMap<>();
        private final Map<Long, Order> allOrders = new LinkedHashMap<>();
        private final List<Trade> trades = new ArrayList<>();
        private final TradeIdGenerator tradeIds = new TradeIdGenerator();
        private final TradeIdGenerator orderIds = new TradeIdGenerator();

        void listSymbol(String symbol, long referencePrice) {
            books.put(symbol, new OrderBook(symbol, referencePrice));
        }

        List<String> symbols() {
            return new ArrayList<>(books.keySet());
        }

        boolean hasSymbol(String symbol) {
            return books.containsKey(symbol);
        }

        OrderBook book(String symbol) {
            return books.get(symbol);
        }

        Account account(String name) {
            return accounts.computeIfAbsent(name,
                    n -> new Account(n, STARTING_CASH, symbols(), STARTING_SHARES));
        }

        Map<String, Account> accounts() {
            return accounts;
        }

        List<Trade> trades() {
            return trades;
        }

        Order order(long id) {
            return allOrders.get(id);
        }

        Order submit(String trader, String symbol, Side side, OrderType type, long price, long quantity) {
            Order order = new Order(orderIds.next(), trader, symbol, side, type, price, quantity);
            allOrders.put(order.id, order);

            String error = validate(order);
            if (error != null) {
                order.status = Status.REJECTED;
                order.message = error;
                return order;
            }

            List<Trade> executed = books.get(symbol).match(order, tradeIds);
            for (Trade t : executed) {
                settle(t);
                trades.add(t);
            }
            return order;
        }

        String cancel(String symbol, long orderId, String trader) {
            if (!books.containsKey(symbol)) {
                return "Unknown symbol";
            }
            return books.get(symbol).cancel(orderId, trader);
        }

        private String validate(Order o) {
            if (!books.containsKey(o.symbol)) {
                return "Unknown symbol " + o.symbol;
            }
            if (o.quantity <= 0) {
                return "Quantity must be positive";
            }
            if (o.quantity > MAX_QUANTITY) {
                return "Quantity exceeds limit of " + MAX_QUANTITY;
            }
            if (o.type == OrderType.LIMIT && o.price <= 0) {
                return "Limit price must be positive";
            }

            Account acc = account(o.trader);
            OrderBook book = books.get(o.symbol);

            if (o.side == Side.SELL) {
                if (acc.position(o.symbol) < o.quantity) {
                    return "Not enough shares: have " + acc.position(o.symbol);
                }
            } else {
                long estimatedPrice;
                if (o.type == OrderType.LIMIT) {
                    estimatedPrice = o.price;
                } else {
                    Long ask = book.bestAsk();
                    estimatedPrice = ask == null ? book.lastPrice() : ask;
                }
                long needed = estimatedPrice * o.quantity;
                if (acc.cash < needed) {
                    return "Not enough cash: need " + Money.format(needed)
                            + ", have " + Money.format(acc.cash);
                }
            }
            return null;
        }

        private void settle(Trade t) {
            Account buyer = account(t.buyer);
            Account seller = account(t.seller);
            buyer.cash -= t.value();
            seller.cash += t.value();
            buyer.addShares(t.symbol, t.quantity);
            seller.addShares(t.symbol, -t.quantity);
        }

        long totalCash() {
            long sum = 0;
            for (Account a : accounts.values()) {
                sum += a.cash;
            }
            return sum;
        }

        long totalShares(String symbol) {
            long sum = 0;
            for (Account a : accounts.values()) {
                sum += a.position(symbol);
            }
            return sum;
        }
    }

    static void heading(String text) {
        System.out.println();
        System.out.println("=== " + text + " ===");
    }

    static void step(String text) {
        System.out.println("  * " + text);
    }

    static void print(Order o) {
        String msg = o.message.isEmpty() ? "" : "  (" + o.message + ")";
        System.out.println("    => " + o + msg);
    }

    static void printTrades(Exchange ex, int fromIndex) {
        for (int i = fromIndex; i < ex.trades().size(); i++) {
            System.out.println("       trade: " + ex.trades().get(i));
        }
    }

    static void printMarket(Exchange ex) {
        System.out.printf("%-6s %10s %10s %10s %8s %8s %8s%n",
                "SYMBOL", "LAST", "HIGH", "LOW", "VOLUME", "BID", "ASK");
        for (String s : ex.symbols()) {
            OrderBook b = ex.book(s);
            System.out.printf("%-6s %10s %10s %10s %8d %8s %8s%n", s,
                    Money.format(b.lastPrice()), Money.format(b.high()), Money.format(b.low()),
                    b.volume(),
                    b.bestBid() == null ? "-" : Money.format(b.bestBid()),
                    b.bestAsk() == null ? "-" : Money.format(b.bestAsk()));
        }
    }

    static void printPortfolio(Exchange ex, String name) {
        Account a = ex.account(name);
        StringBuilder sb = new StringBuilder();
        long marketValue = 0;
        for (Map.Entry<String, Long> e : a.positions.entrySet()) {
            sb.append(String.format("    %-6s %6d shares%n", e.getKey(), e.getValue()));
            marketValue += e.getValue() * ex.book(e.getKey()).lastPrice();
        }
        System.out.printf("  %s: cash=%s, holdings value=%s, total=%s%n",
                a.name, Money.format(a.cash), Money.format(marketValue), Money.format(a.cash + marketValue));
        System.out.print(sb);
    }

    static void printAllPortfolios(Exchange ex) {
        System.out.printf("%-8s %14s %14s %14s%n", "TRADER", "CASH", "HOLDINGS", "TOTAL");
        for (Account a : ex.accounts().values()) {
            long value = 0;
            for (Map.Entry<String, Long> e : a.positions.entrySet()) {
                value += e.getValue() * ex.book(e.getKey()).lastPrice();
            }
            System.out.printf("%-8s %14s %14s %14s%n", a.name,
                    Money.format(a.cash), Money.format(value), Money.format(a.cash + value));
        }
    }

    static void runDemo(Exchange ex) {
        heading("Demo: matching rules");
        int mark;

        step("Alice and Bob post asks for ACME");
        print(ex.submit("ALICE", "ACME", Side.SELL, OrderType.LIMIT, Money.parse("100.50"), 100));
        print(ex.submit("BOB", "ACME", Side.SELL, OrderType.LIMIT, Money.parse("100.60"), 50));

        step("Carol buys 120 @ 100.55: takes Alice's 100, the rest rests in the book");
        mark = ex.trades().size();
        print(ex.submit("CAROL", "ACME", Side.BUY, OrderType.LIMIT, Money.parse("100.55"), 120));
        printTrades(ex, mark);

        step("Dave market buy 30 takes Bob's liquidity");
        mark = ex.trades().size();
        print(ex.submit("DAVE", "ACME", Side.BUY, OrderType.MARKET, 0, 30));
        printTrades(ex, mark);

        step("Price-time priority: two bids at the same price, the earlier one fills first");
        Order first = ex.submit("ALICE", "GLBX", Side.BUY, OrderType.LIMIT, Money.parse("250.00"), 10);
        Order second = ex.submit("BOB", "GLBX", Side.BUY, OrderType.LIMIT, Money.parse("250.00"), 10);
        print(first);
        print(second);
        mark = ex.trades().size();
        print(ex.submit("CAROL", "GLBX", Side.SELL, OrderType.LIMIT, Money.parse("250.00"), 10));
        printTrades(ex, mark);
        System.out.println("       first bid now:  " + first.status);
        System.out.println("       second bid now: " + second.status);

        step("Rejected: Dave tries to sell shares he does not have");
        print(ex.submit("DAVE", "NOVA", Side.SELL, OrderType.LIMIT, Money.parse("42.50"), 5_000));

        step("Rejected: Bob tries to buy more than his cash allows");
        print(ex.submit("BOB", "NOVA", Side.BUY, OrderType.LIMIT, Money.parse("42.50"), 5_000));

        step("Cancel: only the owner can cancel an order");
        Order resting = ex.submit("DAVE", "ACME", Side.BUY, OrderType.LIMIT, Money.parse("99.00"), 10);
        print(resting);
        System.out.println("    BOB cancels:  " + ex.cancel("ACME", resting.id, "BOB"));
        System.out.println("    DAVE cancels: " + ex.cancel("ACME", resting.id, "DAVE"));

        System.out.println();
        System.out.print(ex.book("ACME").format(5));
        System.out.println();
        printMarket(ex);
        System.out.println();
        printAllPortfolios(ex);
    }

    static void checkInvariants(Exchange ex, long expectedCash) {
        heading("Invariant checks");
        boolean ok = ex.totalCash() == expectedCash;
        System.out.println("  total cash unchanged: " + (ok ? "OK" : "FAILED"));
        for (String s : ex.symbols()) {
            long expected = ex.accounts().size() * Exchange.STARTING_SHARES;
            boolean sharesOk = ex.totalShares(s) == expected;
            ok &= sharesOk;
            System.out.printf("  %-5s total shares=%d (expected %d): %s%n",
                    s, ex.totalShares(s), expected, sharesOk ? "OK" : "FAILED");
        }
        System.out.println(ok ? "  All invariants hold." : "  INVARIANT VIOLATION");
    }

    static void runMenu(Exchange ex, Scanner in) {
        heading("Interactive mode");
        boolean running = true;
        while (running) {
            System.out.println();
            System.out.println("1) Place order   2) Cancel order   3) Show book");
            System.out.println("4) Portfolio     5) Market data    6) Trade history   0) Exit");
            System.out.print("> ");
            if (!in.hasNextLine()) {
                break;
            }
            String choice = in.nextLine().trim();
            try {
                switch (choice) {
                    case "1" -> placeOrder(ex, in);
                    case "2" -> cancelOrder(ex, in);
                    case "3" -> {
                        String symbol = ask(in, "Symbol (" + ex.symbols() + "): ").toUpperCase();
                        if (ex.hasSymbol(symbol)) {
                            System.out.print(ex.book(symbol).format(8));
                        } else {
                            System.out.println("Unknown symbol.");
                        }
                    }
                    case "4" -> printPortfolio(ex, ask(in, "Trader name: ").toUpperCase());
                    case "5" -> printMarket(ex);
                    case "6" -> {
                        if (ex.trades().isEmpty()) {
                            System.out.println("No trades yet.");
                        }
                        ex.trades().forEach(t -> System.out.println("  " + t));
                    }
                    case "0" -> running = false;
                    default -> System.out.println("Invalid choice.");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        System.out.println("Goodbye.");
    }

    static String ask(Scanner in, String prompt) {
        System.out.print(prompt);
        return in.hasNextLine() ? in.nextLine().trim() : "";
    }

    static void placeOrder(Exchange ex, Scanner in) {
        String trader = ask(in, "Trader name: ").toUpperCase();
        if (trader.isEmpty()) {
            throw new IllegalArgumentException("Trader name is required");
        }
        String symbol = ask(in, "Symbol (" + ex.symbols() + "): ").toUpperCase();
        Side side = parseSide(ask(in, "Side (BUY/SELL): "));
        OrderType type = parseType(ask(in, "Type (LIMIT/MARKET): "));
        long price = 0;
        if (type == OrderType.LIMIT) {
            price = Money.parse(ask(in, "Limit price: "));
        }
        long quantity = parseLong(ask(in, "Quantity: "));

        int mark = ex.trades().size();
        Order order = ex.submit(trader, symbol, side, type, price, quantity);
        print(order);
        printTrades(ex, mark);
    }

    static void cancelOrder(Exchange ex, Scanner in) {
        String trader = ask(in, "Trader name: ").toUpperCase();
        long id = parseLong(ask(in, "Order id: "));
        Order order = ex.order(id);
        if (order == null) {
            System.out.println("No such order.");
            return;
        }
        System.out.println(ex.cancel(order.symbol, id, trader));
    }

    static Side parseSide(String text) {
        try {
            return Side.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Side must be BUY or SELL");
        }
    }

    static OrderType parseType(String text) {
        try {
            return OrderType.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type must be LIMIT or MARKET");
        }
    }

    static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid whole number: " + text);
        }
    }

    public static void main(String[] args) {
        Exchange exchange = new Exchange();
        exchange.listSymbol("ACME", Money.parse("100.00"));
        exchange.listSymbol("GLBX", Money.parse("250.00"));
        exchange.listSymbol("NOVA", Money.parse("42.50"));

        for (String name : List.of("ALICE", "BOB", "CAROL", "DAVE")) {
            exchange.account(name);
        }
        long expectedCash = exchange.totalCash();

        runDemo(exchange);
        checkInvariants(exchange, expectedCash);

        if (List.of(args).contains("--interactive")) {
            runMenu(exchange, new Scanner(System.in));
        } else {
            System.out.println();
            System.out.println("Tip: run with --interactive to place your own orders.");
        }
    }
}
