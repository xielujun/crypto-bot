package org.achfrag.trading.crypto.bitfinex;

import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.achfrag.trading.crypto.bitfinex.commands.AbstractAPICommand;
import org.achfrag.trading.crypto.bitfinex.commands.AuthCommand;
import org.achfrag.trading.crypto.bitfinex.commands.CancelOrderCommand;
import org.achfrag.trading.crypto.bitfinex.commands.CancelOrderGroupCommand;
import org.achfrag.trading.crypto.bitfinex.commands.CommandException;
import org.achfrag.trading.crypto.bitfinex.commands.OrderCommand;
import org.achfrag.trading.crypto.bitfinex.commands.SubscribeOrderbookCommand;
import org.achfrag.trading.crypto.bitfinex.commands.SubscribeTickerCommand;
import org.achfrag.trading.crypto.bitfinex.entity.APIException;
import org.achfrag.trading.crypto.bitfinex.entity.BitfinexCurrencyPair;
import org.achfrag.trading.crypto.bitfinex.entity.BitfinexOrder;
import org.achfrag.trading.crypto.bitfinex.entity.BitfinexOrderType;
import org.achfrag.trading.crypto.bitfinex.entity.ExchangeOrder;
import org.achfrag.trading.crypto.bitfinex.entity.ExchangeOrderState;
import org.achfrag.trading.crypto.bitfinex.entity.OrderBookFrequency;
import org.achfrag.trading.crypto.bitfinex.entity.OrderBookPrecision;
import org.achfrag.trading.crypto.bitfinex.entity.Wallet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BaseTick;
import org.ta4j.core.Tick;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

public class BitfinexApiBroker {

	/**
	 * The bitfinex api
	 */
	public final static String BITFINEX_URI = "wss://api.bitfinex.com/ws/2";
	
	/**
	 * The API callback
	 */
	private final Consumer<String> apiCallback = ((c) -> websocketCallback(c));
	
	/**
	 * The websocket endpoint
	 */
	private WebsocketClientEndpoint websocketEndpoint;
	
	/**
	 * The channel map
	 */
	private final Map<Integer, String> channelIdSymbolMap;

	/**
	 * The order callbacks
	 */
	private final List<Consumer<ExchangeOrder>> orderCallbacks;

	/**
	 * The tick manager
	 */
	private TickerManager tickerManager;
	
	/**
	 * The last heartbeat value
	 */
	protected final AtomicLong lastHeatbeat;

	/**
	 * The heartbeat thread
	 */
	private Thread heartbeatThread;
	
	/**
	 * The API key
	 */
	private String apiKey;
	
	/**
	 * The API secret
	 */
	private String apiSecret;
	
	/**
	 * The authentification latch
	 */
	private CountDownLatch authenticatedLatch;
	
	/**
	 * The oder snapshot latch
	 */
	private CountDownLatch orderSnapshotLatch;
	
	/**
	 * Is the connection authenticated?
	 */
	private boolean authenticated;
	
	/**
	 * Wallets
	 * 
	 *  Currency, Wallet-Type, Wallet
	 */
	private final Table<String, String, Wallet> walletTable;
	
	/**
	 * The orders
	 */
	private final List<ExchangeOrder> orders;
	
	/**
	 * The Logger
	 */
	final static Logger logger = LoggerFactory.getLogger(BitfinexApiBroker.class);

	public BitfinexApiBroker() {
		this.channelIdSymbolMap = new HashMap<>();
		this.orderCallbacks = new ArrayList<>();
		this.lastHeatbeat = new AtomicLong();
		this.tickerManager = new TickerManager();
		this.walletTable = HashBasedTable.create();
		this.orders = new ArrayList<>();
		this.authenticated = false;
	}
	
	public BitfinexApiBroker(final String apiKey, final String apiSecret) {
		this();
		this.apiKey = apiKey;
		this.apiSecret = apiSecret;
	}
	
	public void connect() throws APIException {
		try {
			final URI bitfinexURI = new URI(BITFINEX_URI);
			websocketEndpoint = new WebsocketClientEndpoint(bitfinexURI);
			websocketEndpoint.addConsumer(apiCallback);
			websocketEndpoint.connect();
			updateConnectionHeartbeat();
			
			executeAuthentification();
			
			heartbeatThread = new Thread(new HeartbeatThread(this));
			heartbeatThread.start();
		} catch (Exception e) {
			throw new APIException(e);
		}
	}

	/**
	 * Execute the authentification and wait until the socket is ready
	 * @throws InterruptedException
	 * @throws APIException 
	 */
	private void executeAuthentification() throws InterruptedException, APIException {
		authenticatedLatch = new CountDownLatch(1);
		orderSnapshotLatch = new CountDownLatch(1);

		if(isAuthenticatedConnection()) {
			sendCommand(new AuthCommand());
			logger.info("Waiting for authentification");
			authenticatedLatch.await(10, TimeUnit.SECONDS);
			
			if(! authenticated) {
				throw new APIException("Unable to perform authentification");
			}
			
			// Wait for order snapshot
			orderSnapshotLatch.await(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Is the connection to be authentificated
	 * @return
	 */
	private boolean isAuthenticatedConnection() {
		return apiKey != null && apiSecret != null;
	}
	
	/**
	 * Was after the connect the authentification successfully?
	 * @return
	 */
	public boolean isAuthenticated() {
		return authenticated;
	}
	
	/**
	 * Disconnect the websocket
	 */
	public void disconnect() {
		
		if(heartbeatThread != null) {
			heartbeatThread.interrupt();
			heartbeatThread = null;
		}
		
		if(websocketEndpoint != null) {
			websocketEndpoint.removeConsumer(apiCallback);
			websocketEndpoint.disconnect();
			websocketEndpoint = null;
		}
	}

	/**
	 * Send a new API command
	 * @param apiCommand
	 */
	public void sendCommand(final AbstractAPICommand apiCommand) {
		try {
			final String command = apiCommand.getCommand(this);
			logger.debug("Sending to server: {}", command);
			websocketEndpoint.sendMessage(command);
		} catch (CommandException e) {
			logger.error("Got Exception while sending command", e);
		}
	}
	
	/**
	 * Get the websocket endpoint
	 * @return
	 */
	public WebsocketClientEndpoint getWebsocketEndpoint() {
		return websocketEndpoint;
	}
	
	/**
	 * We received a websocket callback
	 * @param message
	 */
	private void websocketCallback(final String message) {
		logger.debug("Got message: {}", message);
		
		if(message.startsWith("{")) {
			handleAPICallback(message);
		} else if(message.startsWith("[")) {
			handleChannelCallback(message);
		} else {
			logger.error("Got unknown callback: {}", message);
		}
	}

	/**
	 * Handle a API callback
	 */
	protected void handleAPICallback(final String message) {
		
		logger.debug("Got {}", message);
				
		// JSON callback
		final JSONTokener tokener = new JSONTokener(message);
		final JSONObject jsonObject = new JSONObject(tokener);
		
		final String eventType = jsonObject.getString("event");

		switch (eventType) {
		case "info":
			break;
		case "subscribed":
			handleSubscribedCallback(message, jsonObject);
			break;
		case "pong":
			updateConnectionHeartbeat();
			break;
		case "unsubscribed":
			handleUnsubscribedCallback(jsonObject);
			break;
		case "auth":
			handleAuthCallback(jsonObject);
			break;
		default:
			logger.error("Unknown event: {}", message);
		}
	}

	/**
	 * Handle the authentification callback
	 * @param jsonObject
	 */
	private void handleAuthCallback(final JSONObject jsonObject) {
		
		final String status = jsonObject.getString("status");
		
		logger.info("Authentification callback state {}", status);
		
		if(status.equals("OK")) {
			authenticated = true;
		} else {
			authenticated = false;
			logger.error("Unable to authenticate: {}", jsonObject.toString());
		}
		
		if(authenticatedLatch != null) {
			authenticatedLatch.countDown();
		}
	}

	/**
	 * Handle new channel unsubscribed callbacks
	 * @param jsonObject
	 */
	private void handleUnsubscribedCallback(final JSONObject jsonObject) {
		synchronized (channelIdSymbolMap) {
			final int channelId = jsonObject.getInt("chanId");
			final String symbol = getFromChannelSymbolMap(channelId);
			logger.info("Channel {} ({}) is unsubscribed", channelId, symbol);
			channelIdSymbolMap.remove(channelId);
			channelIdSymbolMap.notifyAll();
		}
	}

	/**
	 * Update the connection heartbeat
	 */
	private void updateConnectionHeartbeat() {
		lastHeatbeat.set(System.currentTimeMillis());
	}

	private void handleSubscribedCallback(final String message, final JSONObject jsonObject) {
		final String channel = jsonObject.getString("channel");

		if (channel.equals("ticker")) {
			final int channelId = jsonObject.getInt("chanId");
			final String symbol = jsonObject.getString("symbol");
			logger.info("Registering symbol {} on channel {}", symbol, channelId);
			addToChannelSymbolMap(channelId, symbol);
		} else if (channel.equals("candles")) {
			final int channelId = jsonObject.getInt("chanId");
			final String key = jsonObject.getString("key");
			logger.info("Registering key {} on channel {}", key, channelId);
			addToChannelSymbolMap(channelId, key);
		} else {
			logger.error("Unknown subscribed callback {}", message);
		}
	}

	/**
	 * Add channel to symbol map
	 * @param channelId
	 * @param symbol
	 */
	private void addToChannelSymbolMap(final int channelId, final String symbol) {
		synchronized (channelIdSymbolMap) {
			channelIdSymbolMap.put(channelId, symbol);
			channelIdSymbolMap.notifyAll();
		}
	}

	/**
	 * Handle a channel callback
	 * @param message
	 */
	protected void handleChannelCallback(final String message) {
		// Channel callback
		logger.debug("Channel callback");
		updateConnectionHeartbeat();

		// JSON callback
		final JSONTokener tokener = new JSONTokener(message);
		final JSONArray jsonArray = new JSONArray(tokener);
				
		final int channel = jsonArray.getInt(0);
		
		if(channel == 0) {
			handleSignalingChannelData(message, jsonArray);
		} else {
			handleChannelData(jsonArray, channel);
		}
	}

	/**
	 * Handle signaling channel data
	 * @param message
	 * @param jsonArray
	 */
	private void handleSignalingChannelData(final String message, final JSONArray jsonArray) {
		
		if(message.contains("ERROR")) {
			logger.error("Got Error message: {}", message);
		}
				
		final String subchannel = jsonArray.getString(1);

		switch (subchannel) {
		// Heartbeat 
		case "hb":
			logger.debug("Got connection heartbeat");
			updateConnectionHeartbeat();
			break;
			
		// Positions
		case "ps":
			// Ignore positions
			break;
		
		// Founding offers
		case "fos":
			// Ignore founding offers
			break;
			
		// Founding credits
		case "fcs":
			// Ignore founding credits
			break;
			
		// Founding loans
		case "fls":
			// Ignore founding loans
			break;
		
		// Ats - Unkown
		case "ats":
			// Ignore
			break;
			
		// Wallet snapshot
		case "ws":
			handleWalletsCallback(jsonArray);
			break;
			
		// Wallet update
		case "wu":
			handleWalletsCallback(jsonArray);
			break;
			
		// Order snapshot
		case "os":
			handleOrdersCallback(jsonArray);
			break;
			
		// Order notification
		case "on":
			handleOrdersCallback(jsonArray);
			break;
			
		// Order update
		case "ou":
			handleOrdersCallback(jsonArray);
			break;
			
		// Order cancelation
		case "oc":
			handleOrdersCallback(jsonArray);
			break;
			
		default:
			logger.error("No match found for message {}", message);
			break;
		}
	}

	/**
	 * Handle the orders callback
	 * @param jsonArray
	 */
	private void handleOrdersCallback(final JSONArray jsonArray) {
		
		logger.info("Got order callback {}", jsonArray.toString());
		
		final JSONArray orders = jsonArray.getJSONArray(2);
		
		// No orders active
		if(orders.length() == 0) {
			return;
		}
		
		// Snapshot or update
		if(! (orders.get(0) instanceof JSONArray)) {
			handleOrderCallback(orders);
		} else {
			for(int orderPos = 0; orderPos < orders.length(); orderPos++) {
				final JSONArray orderArray = orders.getJSONArray(orderPos);
				handleOrderCallback(orderArray);
			}
			
			if(orderSnapshotLatch != null) {
				orderSnapshotLatch.countDown();
			}
		}
	}

	/**
	 * Handle a single order callback
	 * @param orderArray
	 */
	private void handleOrderCallback(final JSONArray order) {		
		final ExchangeOrder exchangeOrder = new ExchangeOrder();
		exchangeOrder.setOrderId(order.getLong(0));
		exchangeOrder.setGroupId(order.optInt(1, -1));
		exchangeOrder.setCid(order.getLong(2));
		exchangeOrder.setSymbol(order.getString(3));
		exchangeOrder.setCreated(order.getLong(4));
		exchangeOrder.setUpdated(order.getLong(5));
		exchangeOrder.setAmount(order.getDouble(6));
		exchangeOrder.setAmountAtCreation(order.getDouble(7));
		exchangeOrder.setOrderType(BitfinexOrderType.fromString(order.getString(8)));
		
		final ExchangeOrderState orderState = ExchangeOrderState.fromString(order.getString(13));
		exchangeOrder.setState(orderState);
		
		exchangeOrder.setPrice(order.getDouble(16));
		exchangeOrder.setPriceAvg(order.optDouble(17, -1));
		exchangeOrder.setPriceTrailing(order.optDouble(18, -1));
		exchangeOrder.setPriceAuxLimit(order.optDouble(19, -1));
		exchangeOrder.setNotify(order.getInt(23) == 1 ? true : false);
		exchangeOrder.setHidden(order.getInt(24) == 1 ? true : false);

		synchronized (orders) {
			// Replace order 
			orders.removeIf(o -> o.getOrderId() == exchangeOrder.getOrderId());
			
			// Remove canceled orders
			if(exchangeOrder.getState() != ExchangeOrderState.STATE_CANCELED) {
				orders.add(exchangeOrder);
			}
						
			orders.notifyAll();
		}
		
		// Notify callbacks
		synchronized (orderCallbacks) {
			orderCallbacks.forEach(c -> c.accept(exchangeOrder));
		}
	}

	/**
	 * Handle the wallets callback
	 * @param jsonArray
	 */
	private void handleWalletsCallback(final JSONArray jsonArray) {
		
		final JSONArray wallets = jsonArray.getJSONArray(2);
		
		// Snapshot or update
		if(! (wallets.get(0) instanceof JSONArray)) {
			handleWalletcallback(wallets);
		} else {
			for(int walletPos = 0; walletPos < wallets.length(); walletPos++) {
				final JSONArray walletArray = wallets.getJSONArray(walletPos);
				handleWalletcallback(walletArray);
			}
		}
	}

	/**
	 * Handle the callback for a single wallet
	 * @param walletArray
	 */
	private void handleWalletcallback(final JSONArray walletArray) {
		final String walletType = walletArray.getString(0);
		final String currency = walletArray.getString(1);
		final double balance = walletArray.getDouble(2);
		final float unsettledInterest = walletArray.getFloat(3);
		final float balanceAvailable = walletArray.optFloat(4, -1);
		
		final Wallet wallet = new Wallet(walletType, currency, balance, unsettledInterest, balanceAvailable);

		synchronized (walletTable) {
			walletTable.put(walletType, currency, wallet);
			walletTable.notifyAll();
		}
	}

	/**
	 * Handle normal channel data
	 * @param jsonArray
	 * @param channel
	 */
	private void handleChannelData(final JSONArray jsonArray, final int channel) {
		if(jsonArray.get(1) instanceof String) {
			final String value = jsonArray.getString(1);
			
			if("hb".equals(value)) {
				final String symbol = channelIdSymbolMap.get(channel);
				tickerManager.updateChannelHeartbeat(symbol);		
			} else {
				logger.error("Unable to process: {}", jsonArray);
			}
		} else {	
			final JSONArray subarray = jsonArray.getJSONArray(1);			
			final String channelSymbol = getFromChannelSymbolMap(channel);
			
			if(channelSymbol == null) {
				logger.error("Unable to determine symbol for channel {}", channel);
				logger.error("Data is {}", jsonArray);
				return;
			}
			
			if(channelSymbol.contains("trade")) {
				handleCandlestickCallback(channelSymbol, subarray);
			} else {
				handleTickCallback(channel, subarray);
			}
		}
	}

	/**
	 * Handle a candlestick callback
	 * @param channel
	 * @param subarray
	 */
	private void handleCandlestickCallback(final String channelSymbol, final JSONArray subarray) {

		// channel symbol trade:1m:tLTCUSD
		final List<Tick> ticksBuffer = new ArrayList<>();
		
		// Snapshots contain multiple Bars, Updates only one
		if(subarray.get(0) instanceof JSONArray) {
			for (int pos = 0; pos < subarray.length(); pos++) {
				final JSONArray parts = subarray.getJSONArray(pos);	
				paseCandlestick(ticksBuffer, parts);
			}
		} else {
			paseCandlestick(ticksBuffer, subarray);
		}
		
		ticksBuffer.sort((t1, t2) -> t1.getEndTime().compareTo(t2.getEndTime()));

		tickerManager.handleTicksList(channelSymbol, ticksBuffer);
	}

	/**
	 * Parse a candlestick from JSON result
	 */
	private void paseCandlestick(final List<Tick> ticksBuffer, final JSONArray parts) {
		// 0 = Timestamp, 1 = Open, 2 = Close, 3 = High, 4 = Low,  5 = Volume
		final Instant i = Instant.ofEpochMilli(parts.getLong(0));
		final ZonedDateTime withTimezone = ZonedDateTime.ofInstant(i, Const.BITFINEX_TIMEZONE);
		
		final double open = parts.getDouble(1);
		final double close = parts.getDouble(2);
		final double high = parts.getDouble(3);
		final double low = parts.getDouble(4);
		final double volume = parts.getDouble(5);
		
		final Tick tick = new BaseTick(withTimezone, open, high, low, close, volume);
		ticksBuffer.add(tick);
	}

	/**
	 * Handle a tick callback
	 * @param channel
	 * @param subarray
	 */
	protected void handleTickCallback(final int channel, final JSONArray subarray) {
				
		// 0 = BID
		// 2 = ASK
		// 6 = Price
		final double price = subarray.getDouble(6);
		final Tick tick = new BaseTick(ZonedDateTime.now(Const.BITFINEX_TIMEZONE), price, price, price, price, price);

		final String symbol = getFromChannelSymbolMap(channel);
		
		tickerManager.handleNewTick(symbol, tick);
	}

	/**
	 * Get the channel from the symbol map - thread safe
	 * @param channel
	 * @return
	 */
	private String getFromChannelSymbolMap(final int channel) {
		synchronized (channelIdSymbolMap) {
			return channelIdSymbolMap.get(channel);
		}
	}
	
	/**
	 * Test whether the ticker is active or not 
	 * @param currencyPair
	 * @return
	 */
	public boolean isTickerActive(final BitfinexCurrencyPair currencyPair) {
		final String currencyString = currencyPair.toBitfinexString();
		
		return getChannelForSymbol(currencyString) != -1;
	}

	/**
	 * Find the channel for the given symol
	 * @param currencyString
	 * @return
	 */
	public Integer getChannelForSymbol(final String currencyString) {
		synchronized (channelIdSymbolMap) {
			return channelIdSymbolMap.entrySet()
					.stream()
					.filter((v) -> v.getValue().equals(currencyString))
					.map((v) -> v.getKey())
					.findAny().orElse(-1);
		}
	}
	
	/**
	 * Perform a reconnect
	 * @return
	 */
	public synchronized boolean reconnect() {
		try {
			logger.info("Performing reconnect");
			authenticated = false;
			
			// Invalidate old data
			tickerManager.invalidateTickerHeartbeat();
			orders.clear();
			
			websocketEndpoint.disconnect();
			websocketEndpoint.connect();
			
			executeAuthentification();
			resubscribeChannels();

			updateConnectionHeartbeat();
			
			return true;
		} catch (Exception e) {
			logger.error("Got exception while reconnect", e);
			websocketEndpoint.disconnect();
			return false;
		}
	}

	/**
	 * Resubscribe the old ticker
	 * @throws InterruptedException
	 * @throws APIException
	 */
	private void resubscribeChannels() throws InterruptedException, APIException {
		final Map<Integer, String> oldChannelIdSymbolMap = new HashMap<>();

		synchronized (channelIdSymbolMap) {
			oldChannelIdSymbolMap.putAll(channelIdSymbolMap);
			channelIdSymbolMap.clear();
			channelIdSymbolMap.notifyAll();
		}
		
		oldChannelIdSymbolMap.entrySet().forEach((e) -> sendCommand(new SubscribeTickerCommand(e.getValue())));
		
		logger.info("Waiting for ticker to resubscribe");
		int execution = 0;
		
		synchronized (channelIdSymbolMap) {		
			while(channelIdSymbolMap.size() != oldChannelIdSymbolMap.size()) {
				
				if(execution > 10) {
					
					// Restore old map for reconnect
					synchronized (channelIdSymbolMap) {
						channelIdSymbolMap.clear();
						channelIdSymbolMap.putAll(oldChannelIdSymbolMap);
					}
					
					throw new APIException("Subscription of ticker failed");
				}
				
				channelIdSymbolMap.wait(500);
				execution++;	
			}
		}
	}
	
	/**
	 * Place a new order
	 * @throws APIException 
	 */
	public void placeOrder(final BitfinexOrder order) throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		logger.info("Executing new order {}", order);
		final OrderCommand orderCommand = new OrderCommand(order);
		sendCommand(orderCommand);
	}
	
	/**
	 * Cancel the given order
	 * @param cid
	 * @param date
	 * @throws APIException 
	 */
	public void cancelOrder(final long id) throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		logger.info("Cancel order with id {}", id);
		final CancelOrderCommand cancelOrder = new CancelOrderCommand(id);
		sendCommand(cancelOrder);
	}
	
	/**
	 * Cancel the given order group
	 * @param cid
	 * @param date
	 * @throws APIException 
	 */
	public void cancelOrderGroup(final int id) throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		logger.info("Cancel order group {}", id);
		final CancelOrderGroupCommand cancelOrder = new CancelOrderGroupCommand(id);
		sendCommand(cancelOrder);
	}
	
	/**
	 * Subscribe the orderbook
	 * @param currencyPair
	 * @param orderBookPrecision
	 * @param orderBookFrequency
	 * @param pricePoints
	 */
	public void subscribeOrderbook(final BitfinexCurrencyPair currencyPair, 
			final OrderBookPrecision orderBookPrecision, final OrderBookFrequency orderBookFrequency, 
			final int pricePoints) {
		
		logger.info("Subscribe to orderbook {}", currencyPair);
		
		final SubscribeOrderbookCommand subscribeOrderbookCommand = 
				new SubscribeOrderbookCommand(currencyPair, orderBookPrecision, 
				orderBookFrequency, pricePoints);
		
		sendCommand(subscribeOrderbookCommand);
	}
	
	/**
	 * Get the last heartbeat value
	 * @return
	 */
	public AtomicLong getLastHeatbeat() {
		return lastHeatbeat;
	}
	
	/**
	 * Get the API key
	 * @return
	 */
	public String getApiKey() {
		return apiKey;
	}
	
	/**
	 * Get the API secret
	 * @return
	 */
	public String getApiSecret() {
		return apiSecret;
	}
	
	/**
	 * Get all wallets
	 * @return 
	 * @throws APIException 
	 */
	public Collection<Wallet> getWallets() throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		synchronized (walletTable) {
			return Collections.unmodifiableCollection(walletTable.values());
		}
	}

	/**
	 * Throw a new exception if called on a unauthenticated connection
	 * @throws APIException
	 */
	private void throwExceptionIfUnauthenticated() throws APIException {
		if(! authenticated) {
			throw new APIException("Unable to perform operation on an unauthenticated connection");
		}
	}
	
	/**
	 * Get the list with exchange orders
	 * @return
	 * @throws APIException 
	 */
	public List<ExchangeOrder> getOrders() throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		synchronized (orders) {
			return Collections.unmodifiableList(orders);
		}
	}
	
	/**
	 * Add a order callback
	 * @param callback
	 */
	public void addOrderCallback(final Consumer<ExchangeOrder> callback) {
		synchronized (orderCallbacks) {
			orderCallbacks.add(callback);
		}
	}
	
	/**
	 * Remove a order callback
	 * @param callback
	 * @return
	 */
	public boolean removeOrderCallback(final Consumer<ExchangeOrder> callback) {
		synchronized (orderCallbacks) {
			return orderCallbacks.remove(callback);
		}
	}

	/**
	 * Get the ticker manager
	 * @return
	 */
	public TickerManager getTickerManager() {
		return tickerManager;
	}

	/**
	 * Register a tick callback
	 * @param symbol
	 * @param callback
	 * @return
	 * @throws APIException
	 */
	public void registerTickCallback(String symbol, BiConsumer<String, Tick> callback) throws APIException {
		tickerManager.registerTickCallback(symbol, callback);
	}

	/**
	 * Remove a tick callback
	 * @param symbol
	 * @param callback
	 * @return
	 * @throws APIException
	 */
	public boolean removeTickCallback(String symbol, BiConsumer<String, Tick> callback) throws APIException {
		return tickerManager.removeTickCallback(symbol, callback);
	}

}
