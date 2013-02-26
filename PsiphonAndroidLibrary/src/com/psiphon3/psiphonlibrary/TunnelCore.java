/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import ch.ethz.ssh2.*;
import ch.ethz.ssh2.Connection.IStopSignalPending;

import com.psiphon3.psiphonlibrary.R;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.TransparentProxyConfig.PsiphonTransparentProxyException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.stericson.RootTools.RootTools;

public class TunnelCore implements Utils.MyLog.ILogger, IStopSignalPending
{
    public enum State
    {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
    private Context m_parentContext = null;
    private Service m_parentService = null;
    private State m_state = State.DISCONNECTED;
    private boolean m_firstStart = true;
    private Thread m_tunnelThread;
    private ServerInterface m_interface;
    private UpgradeDownloader m_upgradeDownloader = null;
    private ServerSelector m_serverSelector = null;
    private boolean m_destroyed = false;
    private Events m_eventsInterface = null;
    private boolean m_useGenericLogMessages = false;

    enum Signal
    {
        STOP_TUNNEL,
        UNEXPECTED_DISCONNECT
    };
    private BlockingQueue<Signal> m_signalQueue;

    static public interface UpgradeDownloader
    {
        /**
         * Begin downloading the upgrade from the server. Download is done in a
         * separate thread. 
         */
        public void start();

        /**
         * Stop an on-going upgrade download.
         */
        public void stop();
    }
    
    public TunnelCore(Context parentContext, Service parentService)
    {
        m_parentContext = parentContext;
        m_parentService = parentService;
    }
    
    // Implementation of android.app.Service.onStartCommand
    public int onStartCommand(Intent intent, int flags, int startId)
    {        
        if (m_firstStart)
        {
            doForeground();
            MyLog.v(R.string.client_version, MyLog.Sensitivity.NOT_SENSITIVE, EmbeddedValues.CLIENT_VERSION);
            startTunnel();
            m_firstStart = false;
        }
        return android.app.Service.START_STICKY;
    }

    // Implementation of android.app.Service.onCreate
    public void onCreate()
    {
        MyLog.logger = this;
        m_interface = new ServerInterface(m_parentContext);
        m_serverSelector = new ServerSelector(m_interface, m_parentContext);
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy()
    {
        m_destroyed = true;

        stopTunnel();

        MyLog.logger = null;
    }

    private void doForeground()
    {
        if (m_parentService == null)
        {
            // Only works with a Service
            return;
        }

        m_parentService.startForeground(R.string.psiphon_service_notification_id, this.createNotification());
    }
    
    private Notification createNotification()
    {
        if (m_parentService == null)
        {
            // Only works with a Service
            return null;
        }

        int contentTextID = -1;
        int iconID = -1;
        
        switch (getState())
        {
        case CONNECTING:
            contentTextID = R.string.psiphon_service_notification_message_connecting;
            iconID = R.drawable.notification_icon_connecting;
            break;
            
        case CONNECTED:
            if (PsiphonData.getPsiphonData().getTunnelWholeDevice())
            {
                contentTextID = R.string.psiphon_running_whole_device;
            }
            else
            {
                contentTextID = R.string.psiphon_running_browser_only;
            }
            
            iconID = R.drawable.notification_icon_connected;
            break;
            
        case DISCONNECTED:
            contentTextID = R.string.psiphon_stopped;
            iconID = R.drawable.notification_icon_disconnected;
            break;
        
        default:
            assert(false);                
        }

        // TODO: default intent if m_eventsInterface is null or returns a null pendingSignalNotification Intent?
        // NOTE that setLatestEventInfo requires a PendingIntent.  And that calls to notify (ie from setState below)
        // require a contentView which is set by setLatestEventInfo.
        assert(m_eventsInterface != null);
        Intent activityIntent = m_eventsInterface.pendingSignalNotification(m_parentService);
        assert(activityIntent != null);
        PendingIntent invokeActivityIntent = 
                PendingIntent.getActivity(
                    m_parentService,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
    
        Notification notification =
                new Notification(
                        iconID,
                        m_parentService.getText(R.string.app_name),
                        System.currentTimeMillis());

        notification.setLatestEventInfo(
            m_parentService,
            m_parentService.getText(R.string.app_name),
            m_parentService.getText(contentTextID),
            invokeActivityIntent); 
        
        return notification;
    }

    /**
     * Utils.MyLog.ILogger implementation
     */
    @Override
    public void log(int priority, String message)
    {
        sendMessage(message, priority);
    }
    
    private synchronized void sendMessage(
            String message,
            int messageClass)
    {
        if (m_eventsInterface != null)
        {
            m_eventsInterface.appendStatusMessage(m_parentContext, message, messageClass);
        }
    }
        
    class PsiphonServerHostKeyVerifier implements ServerHostKeyVerifier
    {
        private String m_expectedHostKey;
        
        PsiphonServerHostKeyVerifier(String expectedHostKey)
        {
            m_expectedHostKey = expectedHostKey;
        }

        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
        {
            return 0 == m_expectedHostKey.compareTo(Utils.Base64.encode(serverHostKey));
        }
    }
    
    public synchronized State getState()
    {
        return m_state;
    }
    
    private synchronized void setState(State newState)
    {
        m_state = newState;
        
        if (!this.m_destroyed && m_parentService != null)
        {
            String ns = Context.NOTIFICATION_SERVICE;
            NotificationManager mNotificationManager =
                    (NotificationManager)m_parentService.getSystemService(ns);
            mNotificationManager.notify(
                    R.string.psiphon_service_notification_id, 
                    createNotification());
        }
    }
    
    class Monitor implements ConnectionMonitor
    {
        private BlockingQueue<Signal> m_signalQueue;

        public Monitor(BlockingQueue<Signal> signalQueue)
        {
            m_signalQueue = signalQueue;
        }

        public void connectionLost(Throwable reason)
        {
            MyLog.e(R.string.ssh_disconnected_unexpectedly, MyLog.Sensitivity.NOT_SENSITIVE);
    
            // 'Add' will do nothing if there's already a pending signal.
            // This is ok: the pending signal is either UNEXPECTED_DISCONNECT
            // or STOP_TUNNEL, and both will result in a tear down.
            m_signalQueue.add(Signal.UNEXPECTED_DISCONNECT);
        }
    }

    private static class TunnelVpnServiceUnexpectedDisconnect extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public TunnelVpnServiceUnexpectedDisconnect()
        {
            super();
        }
    }
    
    private static class TunnelVpnTunnelStop extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public TunnelVpnTunnelStop()
        {
            super();
        }
    }
    
    private void checkSignals(int waitTimeSeconds)
            throws InterruptedException, TunnelVpnServiceUnexpectedDisconnect, TunnelVpnTunnelStop
    {
        Signal signal = m_signalQueue.poll(waitTimeSeconds, TimeUnit.SECONDS);
        
        if (signal != null)
        {
            switch (signal)
            {
            case STOP_TUNNEL:
                throw new TunnelVpnTunnelStop();
            case UNEXPECTED_DISCONNECT:
                throw new TunnelVpnServiceUnexpectedDisconnect();
            }
        }
    }
    
    public boolean isStopSignalPending()
    {
        return m_signalQueue.peek() == Signal.STOP_TUNNEL;
    }
    
    private boolean runTunnelOnce()
    {
        setState(State.CONNECTING);
        
        PsiphonData.getPsiphonData().setTunnelRelayProtocol("");
        PsiphonData.getPsiphonData().setTunnelSessionID("");

        m_interface.start();
        
        // Generate a new client session ID to be included with all subsequent web requests
        // It's also included with the SSH login, for GeoIP region lookup on the server-side
        m_interface.generateNewCurrentClientSessionID();
        
        boolean runAgain = true;
        boolean unexpectedDisconnect = false;
        Connection conn = null;
        DynamicPortForwarder socks = null;
        TransparentProxyPortForwarder transparentProxy = null;
        DnsProxy dnsProxy = null;
        boolean cleanupTransparentProxyRouting = false;
        Socket socket = null;
        boolean cleanupTun2Socks = false;
        
        try
        {
            ServerInterface.ServerEntry entry = m_interface.setCurrentServerEntry();
            if (entry == null)
            {
                MyLog.e(R.string.no_server_entries, MyLog.Sensitivity.NOT_SENSITIVE);
                runAgain = false;
                return runAgain;
            }

            boolean tunnelWholeDevice = PsiphonData.getPsiphonData().getTunnelWholeDevice();
            boolean runVpnService = tunnelWholeDevice && Utils.hasVpnService() && !PsiphonData.getPsiphonData().getVpnServiceUnavailable();
            String tunnelWholeDeviceDNSServer = "8.8.8.8"; // TEMP. TODO: get remote address/port from Psiphon server
            
            if (tunnelWholeDevice && !runVpnService)
            {
                // Check for required root access *before* establishing the SSH connection
                
                MyLog.v(R.string.checking_for_root_access, MyLog.Sensitivity.NOT_SENSITIVE);

                // Check root access
                //
                // Some known Superuser/RootTools/Psiphon limitations:
                // - The root-check timeout will block tunnel shutdown. It's now 10 seconds instead of 5 seconds because it's best
                //   when the user responds within the first time period (see race condition note below). This is mitigated by the
                //   fact that you can't click Quit with the Superuser prompt up.
                // - Clicking Home and presumably other app switch methods loses the Superuser prompt -- but doesn't stop the root-check
                //   waiting on it. The timeout loop will cause the prompt to re-appear (even over the home screen).
                // - There's a frequently exhibiting race condition between clicking the prompt and the timeout, so often you can click
                //   Deny or Allow and get asked again right away. The "remember" option mitigates this. And the increase to 10 seconds
                //   also mitigates this.
                // - Could probably make the root-check timeout not block the tunnel shutdown and so lengthen the timeout, but there's
                //   another limiting factor that keeps that timeout short-ish: the Runtime.getRuntime.exec() hang bug. This code *needs*
                //   to timeout and kill the proc and retry without waiting forever.                
                
                while (true)
                {
                    // The getTunnelWholeDevice option will only be on when the device
                    // is rooted, but our app may still be denied su privileges
                    int result = RootTools.isAccessGiven();
                    if (result == 0)
                    {
                        // Root access denied
                        MyLog.e(R.string.root_access_denied, MyLog.Sensitivity.NOT_SENSITIVE);
                        runAgain = false;
                        return runAgain;                        
                    }
                    else if (result == 1)
                    {
                        // Root access granted
                        break;
                    }
                    else
                    {
                        // Timeout/unknown (user hasn't responded to prompt)
                        // ...fall through to checkSignals and then try again
                    }
                    
                    checkSignals(0);
                }
            }
            
            checkSignals(0);

            m_serverSelector.Run();

            checkSignals(0);
            
            socket = m_serverSelector.firstEntrySocket;
            String ipAddress = m_serverSelector.firstEntryIpAddress;
            if (socket == null)
            {
                return runAgain;
            }
            entry = m_interface.setCurrentServerEntry();
            // TODO: can this happen? handle gracefully 
            assert(entry.ipAddress.equals(ipAddress));
                        
            checkSignals(0);
            
            MyLog.v(R.string.ssh_connecting, MyLog.Sensitivity.NOT_SENSITIVE);

            Map<String, String> diagnosticData = new HashMap<String, String>();
            diagnosticData.put("ipAddress", entry.ipAddress);
            MyLog.g("ConnectingServer", diagnosticData);
            
            conn = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
            Monitor monitor = new Monitor(m_signalQueue);
            conn.connect(
                    socket,
                    new PsiphonServerHostKeyVerifier(entry.sshHostKey),
                    0,
                    PsiphonConstants.SESSION_ESTABLISHMENT_TIMEOUT_MILLISECONDS,
                    this);
            MyLog.v(R.string.ssh_connected, MyLog.Sensitivity.NOT_SENSITIVE);

            checkSignals(0);

            // Client transmits its session ID prepended to the SSH password; the server
            // uses this to associate the tunnel with web requests -- for GeoIP region stats
            String sshPassword = m_interface.getCurrentClientSessionID() + entry.sshPassword;

            MyLog.v(R.string.ssh_authenticating, MyLog.Sensitivity.NOT_SENSITIVE);
            boolean isAuthenticated = conn.authenticateWithPassword(entry.sshUsername, sshPassword);
            if (isAuthenticated == false)
            {
                MyLog.e(R.string.ssh_authentication_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                return runAgain;
            }
            MyLog.v(R.string.ssh_authenticated, MyLog.Sensitivity.NOT_SENSITIVE);

            MyLog.v(R.string.socks_starting, MyLog.Sensitivity.NOT_SENSITIVE);

            // If polipo is already running, we must use the same SOCKS port that polipo is
            // already using as it's parent proxy port.
            if (Polipo.isPolipoThreadRunning())
            {
                if (!Utils.isPortAvailable(PsiphonData.getPsiphonData().getSocksPort()))
                {
                    MyLog.e(R.string.socks_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getSocksPort());
                    runAgain = false;
                    return runAgain;
                }
            }
            else
            {
                int port = Utils.findAvailablePort(PsiphonConstants.SOCKS_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.socks_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setSocksPort(port);
            }

            socks = conn.createDynamicPortForwarder(PsiphonData.getPsiphonData().getSocksPort());
            MyLog.v(R.string.socks_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getSocksPort());

            // The HTTP proxy implementation is provided by Polipo,
            // a native application accessed via JNI. This proxy is
            // chained to our SOCKS proxy.

            // TODO: there's a security concern here - if the HTTP proxy
            // remains running after the main process dies, a malicious
            // app could plug in its own SOCKS proxy and capture all
            // Psiphon browser activity.
            
            Polipo.getPolipo().runForever();

            if (PsiphonData.getPsiphonData().getHttpProxyPort() == 0)
            {
                MyLog.e(R.string.http_proxy_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                runAgain = false;
                return runAgain;
            }

            MyLog.v(R.string.http_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getHttpProxyPort());
            
            // Start transparent proxy, DNS proxy, and iptables config
            
            if (tunnelWholeDevice && !runVpnService)
            {
                // TODO: findAvailablePort is only effective for TCP services
                int port = Utils.findAvailablePort(PsiphonConstants.DNS_PROXY_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.dns_proxy_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setDnsProxyPort(port);
    
                dnsProxy = new DnsProxy(
                                tunnelWholeDeviceDNSServer,
                                53,
                                PsiphonData.getPsiphonData().getDnsProxyPort());
    
                if (!dnsProxy.Start())
                {
                    // If we can't run the local DNS proxy, abort
                    runAgain = false;
                    return runAgain;             
                }
                
                MyLog.v(R.string.dns_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getDnsProxyPort());            
                
                port = Utils.findAvailablePort(PsiphonConstants.TRANSPARENT_PROXY_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.transparent_proxy_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setTransparentProxyPort(port);
    
                transparentProxy = conn.createTransparentProxyForwarder(PsiphonData.getPsiphonData().getTransparentProxyPort());
    
                try
                {
                    TransparentProxyConfig.setupTransparentProxyRouting(m_parentContext);
                    cleanupTransparentProxyRouting = true;
                }
                catch (PsiphonTransparentProxyException e)
                {
                    // If we can't configure the iptables routing, abort
                    MyLog.e(R.string.transparent_proxy_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                    runAgain = false;
                    return runAgain;
                }
                
                MyLog.v(R.string.transparent_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getTransparentProxyPort());
            }
            
            // Run as Android OS VPN
            
            if (tunnelWholeDevice && runVpnService)
            {
                // VpnService backwards compatibility: doVpnProtect/doVpnBuilder are wrapper
                // functions so we don't reference the undefined VpnServer class when this function
                // is loaded.

                String privateIpAddress = Utils.selectPrivateAddress();
                
                if (privateIpAddress == null)
                {
                    MyLog.v(R.string.vpn_service_no_private_address_available, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }

                ParcelFileDescriptor vpnInterfaceFileDescriptor = null;
                
                if (!doVpnProtect(socket)
                    || null == (vpnInterfaceFileDescriptor = doVpnBuilder(privateIpAddress, tunnelWholeDeviceDNSServer)))
                {
                    runAgain = false;
                    if (Utils.isRooted())
                    {
                        // VpnService appears to be broken. Try root mode instead.
                        // TODO: don't fail over to root mode in the not-really-broken revoked edge condition case (e.g., establish() returns null)?
                        PsiphonData.getPsiphonData().setVpnServiceUnavailable(true);
                        runAgain = true;
                    }
                    return runAgain;
                }
                
                MyLog.v(R.string.vpn_service_running, MyLog.Sensitivity.NOT_SENSITIVE);

                String socksServerAddress = "127.0.0.1:" + Integer.toString(PsiphonData.getPsiphonData().getSocksPort());
                String udpgwServerAddress = "127.0.0.1:" + Integer.toString(PsiphonConstants.UDPGW_SERVER_PORT);
                
                cleanupTun2Socks = true;
                
                Tun2Socks.Start(
                        this,
                        vpnInterfaceFileDescriptor,
                        PsiphonConstants.VPN_INTERFACE_MTU,
                        privateIpAddress,
                        PsiphonConstants.VPN_INTERFACE_NETMASK,
                        socksServerAddress,
                        udpgwServerAddress);
                
                // TODO: detect and report: tun2Socks.Start failed; tun2socks run() unexpected exit

                MyLog.v(R.string.tun2socks_running, MyLog.Sensitivity.NOT_SENSITIVE);
            }
            
            // Don't signal unexpected disconnect until we've started
            conn.addConnectionMonitor(monitor);
            
            setState(State.CONNECTED);
            PsiphonData.getPsiphonData().setTunnelRelayProtocol(PsiphonConstants.RELAY_PROTOCOL);
            
            checkSignals(0);

            try
            {
                m_interface.doHandshakeRequest();
                PsiphonData.getPsiphonData().setTunnelSessionID(m_interface.getCurrentServerSessionID());

                if (m_eventsInterface != null)
                {
                    m_eventsInterface.signalHandshakeSuccess(m_parentContext);
                }
            } 
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.e(R.string.PsiphonAndroidService_HandshakeRequestFailed, MyLog.Sensitivity.NOT_SENSITIVE, requestException);

                // Treat this case like a tunnel failure -- we don't want to proceed without
                // a session ID, home page, etc. We don't expect it's likely that the handshake
                // will fail if the tunnel is successfully established.
                throw new IOException();
            }
            
            if (m_useGenericLogMessages)
            {
                MyLog.i(R.string.psiphon_running_generic, MyLog.Sensitivity.NOT_SENSITIVE);                
            }
            else
            {
                MyLog.i(tunnelWholeDevice ? R.string.psiphon_running_whole_device : R.string.psiphon_running_browser_only, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            checkSignals(0);

            try
            {
                m_interface.doConnectedRequest();
            } 
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.PsiphonAndroidService_ConnectedRequestFailed, MyLog.Sensitivity.NOT_SENSITIVE, requestException);
                // Allow the user to continue. Their session might still function correctly.
            }
            
            checkSignals(0);

            if (m_interface.isUpgradeAvailable() && m_upgradeDownloader != null)
            {
                m_upgradeDownloader.start();
            }
            
            try
            {
                // This busy-wait-ish loop is throttled by the `checkSignals(1)`
                // call. It will wait for 1 second before proceeding to the 
                // `doPeriodicWork()` call (which itself only takes action every
                // half-hour).
                while (true)
                {
                    checkSignals(1);
    
                    m_interface.doPeriodicWork(false);
                }
            }
            finally
            {
                m_interface.doPeriodicWork(true);
            }
        }
        catch (IOException e)
        {
            unexpectedDisconnect = true;

            // SSH errors -- tunnel problems -- result in IOException
            // Make sure we try a different server (if any) next time
            // Note: we're not marking the server failed if handshake/connected requests failed
            
            m_interface.markCurrentServerFailed();

            // TODO: This prints too much info -- the stack trace, but also IP
            // address (not sure if we want to obscure that or not...) 
            //MyLog.e(R.string.error_message, e);
            MyLog.e(R.string.ssh_connection_failed, MyLog.Sensitivity.NOT_SENSITIVE);
        }
        catch (TunnelVpnServiceUnexpectedDisconnect e)
        {
            // NOTE: Not calling MarkCurrentServerFailed(), although there
            // may be a problem with the server. This exception is thrown
            // in the case where the tunnel was successfully established
            // and the connection monitor detected a disconnect. We'll
            // retry the current server at least once. If it still is
            // down -- i.e., not an intermittent problem, we'll expect
            // an IOException on the connection attempt, which will call
            // calling MarkCurrentServerFailed().

            unexpectedDisconnect = true;
            runAgain = true;
        }
        catch (TunnelVpnTunnelStop e)
        {
            unexpectedDisconnect = false;
            runAgain = false;
        }
        catch (InterruptedException e)
        {
            runAgain = false;
        }
        finally
        {
            PsiphonData.getPsiphonData().setTunnelRelayProtocol("");
            PsiphonData.getPsiphonData().setTunnelSessionID("");

            // Abort any outstanding HTTP requests.
            // Currently this would only be the upgrade download request.
            // Otherwise the call below to m_upgradeDownloader.stop() would block.
            m_interface.stop();

            if (cleanupTransparentProxyRouting)
            {
                try
                {
                    TransparentProxyConfig.teardownTransparentProxyRouting(m_parentContext);
                }
                catch (PsiphonTransparentProxyException e)
                {
                }
            }
            
            if (dnsProxy != null)
            {
                dnsProxy.Stop();
                MyLog.v(R.string.dns_proxy_stopped, MyLog.Sensitivity.NOT_SENSITIVE);                
            }
            
            if (transparentProxy != null)
            {
                try
                {
                    transparentProxy.close();
                }
                catch (IOException e)
                {
                    // Ignore
                }
                MyLog.v(R.string.transparent_proxy_stopped, MyLog.Sensitivity.NOT_SENSITIVE);                
            }
            
            if (cleanupTun2Socks)
            {
                Tun2Socks.Stop();
                MyLog.v(R.string.tun2socks_stopped, MyLog.Sensitivity.NOT_SENSITIVE);                
            }
            
            if (socks != null)
            {
                try
                {
                    socks.close();
                }
                catch (IOException e)
                {
                    // Ignore
                }
                MyLog.v(R.string.socks_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            if (conn != null)
            {
                conn.clearConnectionMonitors();
                conn.close();
                MyLog.v(R.string.ssh_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
            }
            
            if (m_upgradeDownloader != null)
            {
                m_upgradeDownloader.stop();
            }

            if (!runAgain)
            {
                setState(State.DISCONNECTED);
            }
            
            if (unexpectedDisconnect && !isStopSignalPending())
            {
                // This will invoke the status activity to show that
                // the tunnel is disconnected. Since that invocation
                // will also restart the tunnel, be sure not to do
                // it when a stop is signaled.
                
                if (m_eventsInterface != null)
                {
                    m_eventsInterface.signalUnexpectedDisconnect(m_parentContext);
                }
            }
            
            if (socket != null)
            {
                try
                {
                    socket.close();
                }
                catch (IOException e)
                {
                }
            }
        }
        
        return runAgain;
    }
    
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private boolean doVpnProtect(Socket socket)
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);

        if (!((TunnelVpnService)m_parentService).protect(socket))
        {
            MyLog.e(R.string.vpn_service_failed, MyLog.Sensitivity.NOT_SENSITIVE, "protect socket failed");
            return false;
        }
        return true;
    }
    
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private ParcelFileDescriptor doVpnBuilder(String privateIpAddress, String tunnelWholeDeviceDNSServer)
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);

        ParcelFileDescriptor vpnInterfaceFileDescriptor = null;
        String builderErrorMessage = null;
        try
        {
            VpnService.Builder builder = ((TunnelVpnService)m_parentService).newBuilder();
            vpnInterfaceFileDescriptor = builder
                    .setSession(m_parentService.getString(R.string.app_name))
                    .setMtu(PsiphonConstants.VPN_INTERFACE_MTU)
                    .addAddress(privateIpAddress, 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(tunnelWholeDeviceDNSServer)
                    .establish();
            if (vpnInterfaceFileDescriptor == null)
            {
                // as per http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29
                builderErrorMessage = "application is not prepared or revoked";
            }
        }
        catch(IllegalArgumentException e)
        {
            builderErrorMessage = e.getMessage();
        }
        catch(IllegalStateException e)
        {
            builderErrorMessage = e.getMessage();                    
        }
        catch(SecurityException e)
        {
            builderErrorMessage = e.getMessage();                    
        }
        if (vpnInterfaceFileDescriptor == null)
        {
            // If we can't configure the Android OS VPN, abort
            MyLog.e(R.string.vpn_service_failed, MyLog.Sensitivity.NOT_SENSITIVE, builderErrorMessage);
        }
        
        return vpnInterfaceFileDescriptor;
    }
    
    private void runTunnel() throws InterruptedException
    {
        if (!m_interface.serverWithCapabilitiesExists(PsiphonConstants.REQUIRED_CAPABILITIES_FOR_TUNNEL))
        {
            setState(State.DISCONNECTED);
            MyLog.e(R.string.no_server_entries, MyLog.Sensitivity.NOT_SENSITIVE);
            return;
        }
        
        while (runTunnelOnce())
        {
            try
            {
                checkSignals(0);
            } 
            catch (TunnelVpnServiceUnexpectedDisconnect e)
            {
                // Continue with the retry loop
            } 
            catch (TunnelVpnTunnelStop e)
            {
                // Stop has been requested, so get out of the retry loop.
                setState(State.DISCONNECTED);
                break;
            }
            
            // Provide visual feedback (notification icon) that we are no longer connected
            setState(State.CONNECTING);
            
            try
            {
                m_interface.start();
                m_interface.fetchRemoteServerList();
            }
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.TunnelService_FetchRemoteServerListFailed, MyLog.Sensitivity.NOT_SENSITIVE, requestException);
            }
            finally
            {
                m_interface.stop();
            }

            // 1-2 second delay before retrying
            // (same as Windows client, see comment in ConnectionManager.cpp)
            try
            {
                Thread.sleep(1000 + (long)(Math.random()*1000.0));
            }
            catch (InterruptedException ie)
            {
                setState(State.DISCONNECTED);
                break;
            }
        }
    }
    
    public void startTunnel()
    {
        stopTunnel();

        if (m_eventsInterface != null)
        {
            m_eventsInterface.signalTunnelStarting(m_parentContext);
        }

        MyLog.v(R.string.starting_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

        setState(State.CONNECTING);
        
        // Only allow 1 signal at a time. A backlog of signals will break the retry loop.
        m_signalQueue = new ArrayBlockingQueue<Signal>(1);

        m_tunnelThread = new Thread(
            new Runnable()
            {
                public void run()
                {
                    try
                    {
                        runTunnel();
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            });

        m_tunnelThread.start();
    }
    
    public void signalUnexpectedDisconnect()
    {
        // Override STOP_TUNNEL; TODO: race condition?
        m_signalQueue.clear();
        m_signalQueue.offer(Signal.UNEXPECTED_DISCONNECT);
    }
    
    public void stopVpnServiceHelper()
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);

        // A hack to stop the VpnService, which doesn't respond to normal
        // stopService() calls.

        // Stopping tun2socks will close the VPN interface fd, which
        // in turn stops the VpnService. Without closing the fd, the
        // stopService call has no effect and the only way to stop
        // the VPN is via the OS notification UI.
        Tun2Socks.Stop();

        // Sometimes we're in the state where there's no fd, and the
        // service still isn't responding to external stopService() calls.
        // For example, when stuck in the waiting-for-connectivity check
        // in ServerSelector.
        m_parentService.stopForeground(true);
        m_parentService.stopSelf();
    }
    
    public void stopTunnel()
    {
        if (m_tunnelThread != null)
        {
            if (m_eventsInterface != null)
            {
                m_eventsInterface.signalTunnelStopping(m_parentContext);
            }

            // TODO: ServerListReorder lifetime on Android isn't the same as on Windows
            if (m_serverSelector != null)
            {
                m_serverSelector.Abort();
                m_serverSelector = null;
            }
            
            // Override UNEXPECTED_DISCONNECT; TODO: race condition?
            m_signalQueue.clear();
            m_signalQueue.offer(Signal.STOP_TUNNEL);

            MyLog.v(R.string.stopping_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
            
            // Tell the ServerInterface to stop (e.g., kill requests).

            // Currently, all requests are run in the context of the
            // tunnel thread; m_interface.outstandingRequests is not
            // a work queue, it's just a way for another thread to
            // reference the requests and invoke .abort(). Any
            // request that should not abort when the tunnel thread
            // should shut down should be omitted from the
            // outstandingRequests list.

            m_interface.stop();
            
            try
            {
                m_tunnelThread.join();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            MyLog.v(R.string.stopped_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
            MyLog.e(R.string.psiphon_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
        }
        
        m_signalQueue = null;
        m_tunnelThread = null;
    }
    
    public void setEventsInterface(Events eventsInterface)
    {
        m_eventsInterface = eventsInterface;
    }
    
    public void setUpgradeDownloader(UpgradeDownloader downloader)
    {
        m_upgradeDownloader = downloader;
    }
    
    public ServerInterface getServerInterface()
    {
        return m_interface;
    }
    
    public void setUseGenericLogMessages(boolean useGenericLogMessages)
    {
        m_useGenericLogMessages = useGenericLogMessages;
    }
}