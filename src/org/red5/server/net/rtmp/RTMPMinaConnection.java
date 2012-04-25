/*
 * RED5 Open Source Flash Server - http://code.google.com/p/red5/
 * 
 * Copyright 2006-2012 by respective authors (see below). All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.red5.server.net.rtmp;

import java.beans.ConstructorProperties;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.CloseFuture;
import org.apache.mina.core.session.IoSession;
import org.red5.server.api.IScope;
import org.red5.server.jmx.mxbeans.RTMPMinaConnectionMXBean;
import org.red5.server.net.protocol.ProtocolState;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.ClientBW;
import org.red5.server.net.rtmp.event.ServerBW;
import org.red5.server.net.rtmp.message.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedResource;

/**
 * Represents an RTMP connection using Mina.
 * 
 * @see "http://mina.apache.org/report/trunk/apidocs/org/apache/mina/core/session/IoSession.html"
 * 
 * @author Paul Gregoire
 */
@ManagedResource
public class RTMPMinaConnection extends RTMPConnection implements RTMPMinaConnectionMXBean {

	protected static Logger log = LoggerFactory.getLogger(RTMPMinaConnection.class);

	/**
	 * MINA I/O session, connection between two end points
	 */
	private volatile IoSession ioSession;

	/**
	 * MBean object name used for de/registration purposes.
	 */
	private volatile ObjectName oName;

	protected int defaultServerBandwidth = 10000000;

	protected int defaultClientBandwidth = 10000000;
	
	protected boolean bandwidthDetection = true;

	{
		log.debug("RTMPMinaConnection created");
	}

	/** Constructs a new RTMPMinaConnection. */
	@ConstructorProperties(value = { "persistent" })
	public RTMPMinaConnection() {
		super(PERSISTENT);
	}

	/** {@inheritDoc} */
	@Override
	public void close() {
		super.close();
		if (ioSession != null) {
			IoFilterChain filters = ioSession.getFilterChain();
			//check if it exists and remove
			if (filters.contains("bandwidthFilter")) {
				ioSession.getFilterChain().remove("bandwidthFilter");
			}
			// update our state
			if (ioSession.containsAttribute(ProtocolState.SESSION_KEY)) {
				RTMP rtmp = (RTMP) ioSession.getAttribute(ProtocolState.SESSION_KEY);
				log.debug("RTMP state: {}", rtmp);
				rtmp.setState(RTMP.STATE_DISCONNECTING);
			}
			// accept no further incoming data
			ioSession.suspendRead();
			// close now, no flushing, no waiting
			CloseFuture future = ioSession.close(true);
			// wait for one second
			try {
				future.await(1000L);
			} catch (InterruptedException e) {
				log.trace("Exception waiting for close", e);
			}
			if (future.isClosed()) {
				log.debug("Connection is closed");
			} else {
				log.debug("Connection is not yet closed");				
			}
		}
		//de-register with JMX
		unregisterJMX();
	}

	@SuppressWarnings("cast")
	@Override
	public boolean connect(IScope newScope, Object[] params) {
		log.debug("Connect scope: {}", newScope);
		boolean success = super.connect(newScope, params);
		if (success) {
			// tell the flash player how fast we want data and how fast we shall send it
			getChannel(2).write(new ServerBW(defaultServerBandwidth));
			// second param is the limit type (0=hard,1=soft,2=dynamic)
			getChannel(2).write(new ClientBW(defaultClientBandwidth, (byte) limitType));
			//if the client is null for some reason, skip the jmx registration
			if (client != null) {
				// perform bandwidth detection
				if (bandwidthDetection && !client.isBandwidthChecked()) {
					client.checkBandwidth();
				}
			} else {
				log.warn("Client was null");
			}
			registerJMX();
		}
		return success;
	}

	/**
	 * Return MINA I/O session.
	 *
	 * @return MINA O/I session, connection between two end-points
	 */
	public IoSession getIoSession() {
		return ioSession;
	}

	/**
	 * @return the defaultServerBandwidth
	 */
	public int getDefaultServerBandwidth() {
		return defaultServerBandwidth;
	}

	/**
	 * @param defaultServerBandwidth the defaultServerBandwidth to set
	 */
	public void setDefaultServerBandwidth(int defaultServerBandwidth) {
		this.defaultServerBandwidth = defaultServerBandwidth;
	}

	/**
	 * @return the defaultClientBandwidth
	 */
	public int getDefaultClientBandwidth() {
		return defaultClientBandwidth;
	}

	/**
	 * @param defaultClientBandwidth the defaultClientBandwidth to set
	 */
	public void setDefaultClientBandwidth(int defaultClientBandwidth) {
		this.defaultClientBandwidth = defaultClientBandwidth;
	}

	/**
	 * @return the limitType
	 */
	public int getLimitType() {
		return limitType;
	}

	/**
	 * @param limitType the limitType to set
	 */
	public void setLimitType(int limitType) {
		this.limitType = limitType;
	}

	/**
	 * @return the bandwidthDetection
	 */
	public boolean isBandwidthDetection() {
		return bandwidthDetection;
	}

	/**
	 * @param bandwidthDetection the bandwidthDetection to set
	 */
	public void setBandwidthDetection(boolean bandwidthDetection) {
		this.bandwidthDetection = bandwidthDetection;
	}
	
	/** {@inheritDoc} */
	@Override
	public long getPendingMessages() {
		if (ioSession != null) {
			return ioSession.getScheduledWriteMessages();
		}
		return 0;
	}

	/** {@inheritDoc} */
	@Override
	public long getReadBytes() {
		if (ioSession != null) {
			return ioSession.getReadBytes();
		}
		return 0;
	}

	/** {@inheritDoc} */
	@Override
	public long getWrittenBytes() {
		if (ioSession != null) {
			return ioSession.getWrittenBytes();
		}
		return 0;
	}

	public void invokeMethod(String method) {
		invoke(method);
	}

	/** {@inheritDoc} */
	@Override
	public boolean isConnected() {
		// XXX Paul: not sure isClosing is actually working as we expect here
		return super.isConnected() && (ioSession != null && ioSession.isConnected()); // && !ioSession.isClosing();
	}

	/** {@inheritDoc} */
	@Override
	protected void onInactive() {
		this.close();
	}

	/** {@inheritDoc} */
	@Override
	public void rawWrite(IoBuffer out) {
		if (ioSession != null) {
			ioSession.write(out);
		}
	}

	/**
	 * Setter for MINA I/O session (connection).
	 *
	 * @param protocolSession  Protocol session
	 */
	public void setIoSession(IoSession protocolSession) {
		SocketAddress remote = protocolSession.getRemoteAddress();
		if (remote instanceof InetSocketAddress) {
			remoteAddress = ((InetSocketAddress) remote).getAddress().getHostAddress();
			remotePort = ((InetSocketAddress) remote).getPort();
		} else {
			remoteAddress = remote.toString();
			remotePort = -1;
		}
		remoteAddresses = new ArrayList<String>(1);
		remoteAddresses.add(remoteAddress);
		remoteAddresses = Collections.unmodifiableList(remoteAddresses);
		this.ioSession = protocolSession;
	}

	/** {@inheritDoc} */
	@Override
	public void write(Packet out) {
		if (ioSession != null) {
			writingMessage(out);
			ioSession.write(out);
		}
	}
	
	protected void registerJMX() {
		// register with jmx
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		try {
			String cName = this.getClass().getName();
			if (cName.indexOf('.') != -1) {
				cName = cName.substring(cName.lastIndexOf('.')).replaceFirst("[\\.]", "");
			}
			String hostStr = host;
			int port = 1935;
			if (host != null && host.indexOf(":") > -1) {
				String[] arr = host.split(":");
				hostStr = arr[0];
				port = Integer.parseInt(arr[1]);
			}
			// Create a new mbean for this instance
			oName = new ObjectName(String.format("org.red5.server:type=%s,connectionType=%s,host=%s,port=%d,clientId=%s",cName, type, hostStr, port, client.getId() ));
	        mbs.registerMBean(this, oName);
		} catch (Exception e) {
			log.warn("Error on jmx registration", e);
		}	
	}
	
	protected void unregisterJMX() {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		try {
			mbs.unregisterMBean(oName);
		} catch (Exception e) {
			log.warn("Exception unregistering: {}", oName, e);
		}
		oName = null;
	}

}
