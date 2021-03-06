package org.achfrag.trading.crypto.bitfinex.commands;

import org.achfrag.trading.crypto.bitfinex.BitfinexApiBroker;
import org.json.JSONObject;

public class PingCommand extends AbstractAPICommand {

	@Override
	public String getCommand(final BitfinexApiBroker bitfinexApiBroker) {
		final JSONObject subscribeJson = new JSONObject();
		subscribeJson.put("event", "ping");
		return subscribeJson.toString();
	}

}
