package org.red5.server.webapp.oflaDemo;

import org.red5.server.adapter.ApplicationAdapter;
import org.red5.server.api.IConnection;
import org.red5.server.api.stream.IStreamCapableConnection;
import org.red5.server.api.stream.support.SimpleBandwidthConfigure;

public class Application extends ApplicationAdapter {

	@Override
	public boolean appConnect(IConnection conn, Object[] params) {
		if (conn instanceof IStreamCapableConnection) {
			IStreamCapableConnection streamConn = (IStreamCapableConnection) conn;
			SimpleBandwidthConfigure sbc = new SimpleBandwidthConfigure();
			sbc.setMaxBurst(102400);
			sbc.setBurst(51200);
			sbc.setOverallBandwidth(1024000);
			streamConn.setBandwidthConfigure(sbc);
		}
		return super.appConnect(conn, params);
	}

}