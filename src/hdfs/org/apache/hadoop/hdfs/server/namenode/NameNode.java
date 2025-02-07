/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Trash;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HDFSPolicyProvider;
import org.apache.hadoop.hdfs.hoss.cache.HotObject;
import org.apache.hadoop.hdfs.hoss.db.HosMetaData;
import org.apache.hadoop.hdfs.hoss.db.PathPosition;
import org.apache.hadoop.hdfs.hoss.smallobject.SmallObjectsManager;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.UnregisteredDatanodeException;
import org.apache.hadoop.hdfs.security.token.block.ExportedBlockKeys;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.common.UpgradeStatusReport;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem.CompleteFileStatus;
import org.apache.hadoop.hdfs.server.namenode.metrics.NameNodeInstrumentation;
import org.apache.hadoop.hdfs.server.namenode.web.resources.NamenodeWebHdfsMethods;
import org.apache.hadoop.hdfs.server.protocol.BlocksWithLocations;
import org.apache.hadoop.hdfs.server.protocol.DatanodeCommand;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.UpgradeCommand;
import org.apache.hadoop.hdfs.web.AuthFilter;
import org.apache.hadoop.hdfs.web.WebHdfsFileSystem;
import org.apache.hadoop.hdfs.web.resources.Param;
import org.apache.hadoop.http.HttpServer;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RPC.Server;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.Groups;
import org.apache.hadoop.security.RefreshUserMappingsProtocol;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.authorize.RefreshAuthorizationPolicyProtocol;
import org.apache.hadoop.security.authorize.ServiceAuthorizationManager;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.StringUtils;


/**********************************************************
 * NameNode serves as both directory namespace manager and "inode table" for the
 * Hadoop DFS. There is a single NameNode running in any DFS deployment. (Well,
 * except when there is a second backup/failover NameNode.)
 * 
 * The NameNode controls two critical tables: 1) filename->blocksequence
 * (namespace) 2) block->machinelist ("inodes")
 * 
 * The first table is stored on disk and is very precious. The second table is
 * rebuilt every time the NameNode comes up.
 * 
 * 'NameNode' refers to both this class as well as the 'NameNode server'. The
 * 'FSNamesystem' class actually performs most of the filesystem management. The
 * majority of the 'NameNode' class itself is concerned with exposing the IPC
 * interface and the http server to the outside world, plus some configuration
 * management.
 * 
 * NameNode implements the ClientProtocol interface, which allows clients to ask
 * for DFS services. ClientProtocol is not designed for direct use by authors of
 * DFS client code. End-users should instead use the
 * org.apache.nutch.hadoop.fs.FileSystem class.
 * 
 * NameNode also implements the DatanodeProtocol interface, used by DataNode
 * programs that actually store DFS data blocks. These methods are invoked
 * repeatedly and automatically by all the DataNodes in a DFS deployment.
 * 
 * NameNode also implements the NamenodeProtocol interface, used by secondary
 * namenodes or rebalancing processes to get partial namenode's state, for
 * example partial blocksMap etc.
 **********************************************************/
public class NameNode implements ClientProtocol, DatanodeProtocol,
		NamenodeProtocol, FSConstants, RefreshAuthorizationPolicyProtocol,
		RefreshUserMappingsProtocol, Runnable {
	static {
		Configuration.addDefaultResource("hdfs-default.xml");
		Configuration.addDefaultResource("hdfs-site.xml");
		Configuration.addDefaultResource("hoss-site.xml");
	}

	public long getProtocolVersion(String protocol, long clientVersion)
			throws IOException {
		if (protocol.equals(ClientProtocol.class.getName())) {
			return ClientProtocol.versionID;
		} else if (protocol.equals(DatanodeProtocol.class.getName())) {
			return DatanodeProtocol.versionID;
		} else if (protocol.equals(NamenodeProtocol.class.getName())) {
			return NamenodeProtocol.versionID;
		} else if (protocol.equals(RefreshAuthorizationPolicyProtocol.class
				.getName())) {
			return RefreshAuthorizationPolicyProtocol.versionID;
		} else if (protocol.equals(RefreshUserMappingsProtocol.class.getName())) {
			return RefreshUserMappingsProtocol.versionID;
		} else {
			throw new IOException("Unknown protocol to name node: " + protocol);
		}
	}

	public static final int DEFAULT_PORT = 8020;

	public static final Log LOG = LogFactory.getLog(NameNode.class.getName());
	public static final Log stateChangeLog = LogFactory
			.getLog("org.apache.hadoop.hdfs.StateChange");
	public FSNamesystem namesystem; // TODO: This should private. Use
									// getNamesystem() instead.
	/** RPC server */
	private Server server;

	/** hos meta data database **/
	private static HosMetaData metaDataDb = null;

	/**
	 * RPC server for HDFS Services communication. BackupNode, Datanodes and all
	 * other services should be connecting to this server if it is configured.
	 * Clients should only go to NameNode#server
	 */
	private Server serviceRpcServer;

	/** RPC server address */
	private InetSocketAddress serverAddress = null;
	/** RPC server for DN address */
	protected InetSocketAddress serviceRPCAddress = null;
	/** httpServer */
	private HttpServer httpServer;
	/** HTTP server address */
	private InetSocketAddress httpAddress = null;
	private Thread emptier;
	/** only used for testing purposes */
	private boolean stopRequested = false;
	/** Is service level authorization enabled? */
	private boolean serviceAuthEnabled = false;

	/**
	 * Format a new filesystem. Destroys any filesystem that may already exist
	 * at this location.
	 **/
	public static void format(Configuration conf) throws IOException {
		format(conf, false);
	}

	static NameNodeInstrumentation myMetrics;

	public FSNamesystem getNamesystem() {
		return namesystem;
	}

	public static NameNodeInstrumentation getNameNodeMetrics() {
		return myMetrics;
	}

	public static InetSocketAddress getAddress(String address) {
		return NetUtils.createSocketAddr(address, DEFAULT_PORT);
	}

	/**
	 * Set the configuration property for the service rpc address to address
	 */
	public static void setServiceAddress(Configuration conf, String address) {
		LOG.info("Setting ADDRESS " + address);
		conf.set(DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY, address);
	}

	/**
	 * Fetches the address for services to use when connecting to namenode based
	 * on the value of fallback returns null if the special address is not
	 * specified or returns the default namenode address to be used by both
	 * clients and services. Services here are datanodes, backup node, any non
	 * client connection
	 */
	public static InetSocketAddress getServiceAddress(Configuration conf,
			boolean fallback) {
		String addr = conf
				.get(DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY);
		if (addr == null || addr.isEmpty()) {
			return fallback ? getAddress(conf) : null;
		}
		return getAddress(addr);
	}

	public static InetSocketAddress getAddress(Configuration conf) {
		return getAddress(FileSystem.getDefaultUri(conf).toString());
	}

	public static URI getUri(InetSocketAddress namenode) {
		int port = namenode.getPort();
		String portString = port == DEFAULT_PORT ? "" : (":" + port);
		return URI.create("hdfs://" + namenode.getHostName() + portString);
	}

	/**
	 * Given a configuration get the address of the service rpc server If the
	 * service rpc is not configured returns null
	 */
	protected InetSocketAddress getServiceRpcServerAddress(Configuration conf)
			throws IOException {
		return NameNode.getServiceAddress(conf, false);
	}

	/**
	 * Modifies the configuration passed to contain the service rpc address
	 * setting
	 */
	protected void setRpcServiceServerAddress(Configuration conf) {
		String address = serviceRPCAddress.getHostName() + ":"
				+ serviceRPCAddress.getPort();
		setServiceAddress(conf, address);
	}

	/**
	 * Initialize name-node.
	 * 
	 * @param conf
	 *            the configuration
	 */
	@SuppressWarnings("deprecation")
	private void initialize(Configuration conf) throws IOException {
		InetSocketAddress socAddr = NameNode.getAddress(conf);
		UserGroupInformation.setConfiguration(conf);
		SecurityUtil
				.login(conf, DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY,
						DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY,
						socAddr.getHostName());
		int handlerCount = conf.getInt("dfs.namenode.handler.count", 10);

		// set service-level authorization security policy
		if (serviceAuthEnabled = conf
				.getBoolean(
						ServiceAuthorizationManager.SERVICE_AUTHORIZATION_CONFIG,
						false)) {
			ServiceAuthorizationManager.refresh(conf, new HDFSPolicyProvider());
		}

		myMetrics = NameNodeInstrumentation.create(conf);
		this.namesystem = new FSNamesystem(this, conf);

		if (UserGroupInformation.isSecurityEnabled()) {
			namesystem.activateSecretManager();
		}

		// create rpc server
		InetSocketAddress dnSocketAddr = getServiceRpcServerAddress(conf);
		if (dnSocketAddr != null) {
			int serviceHandlerCount = conf.getInt(
					DFSConfigKeys.DFS_NAMENODE_SERVICE_HANDLER_COUNT_KEY,
					DFSConfigKeys.DFS_NAMENODE_SERVICE_HANDLER_COUNT_DEFAULT);
			this.serviceRpcServer = RPC.getServer(this,
					dnSocketAddr.getHostName(), dnSocketAddr.getPort(),
					serviceHandlerCount, false, conf,
					namesystem.getDelegationTokenSecretManager());
			this.serviceRPCAddress = this.serviceRpcServer.getListenerAddress();
			setRpcServiceServerAddress(conf);
		}
		this.server = RPC.getServer(this, socAddr.getHostName(),
				socAddr.getPort(), handlerCount, false, conf,
				namesystem.getDelegationTokenSecretManager());

		// The rpc-server port can be ephemeral... ensure we have the correct
		// info
		this.serverAddress = this.server.getListenerAddress();
		FileSystem.setDefaultUri(conf, getUri(serverAddress));
		LOG.info("Namenode up at: " + this.serverAddress);

		startHttpServer(conf);
		this.server.start(); // start RPC server
		if (serviceRpcServer != null) {
			serviceRpcServer.start();
		}
		startTrashEmptier(conf);
	}

	private void startTrashEmptier(Configuration conf) throws IOException {
		this.emptier = new Thread(new Trash(conf).getEmptier(), "Trash Emptier");
		this.emptier.setDaemon(true);
		this.emptier.start();
	}

	@SuppressWarnings("deprecation")
	public static String getInfoServer(Configuration conf) {
		String http = UserGroupInformation.isSecurityEnabled() ? "dfs.https.address"
				: "dfs.http.address";
		return NetUtils.getServerAddress(conf, "dfs.info.bindAddress",
				"dfs.info.port", http);
	}

	@SuppressWarnings("deprecation")
	private void startHttpServer(final Configuration conf) throws IOException {
		final String infoAddr = NetUtils.getServerAddress(conf,
				"dfs.info.bindAddress", "dfs.info.port", "dfs.http.address");
		final InetSocketAddress infoSocAddr = NetUtils
				.createSocketAddr(infoAddr);
		if (UserGroupInformation.isSecurityEnabled()) {
			String httpsUser = SecurityUtil.getServerPrincipal(conf
					.get(DFSConfigKeys.DFS_NAMENODE_KRB_HTTPS_USER_NAME_KEY),
					infoSocAddr.getHostName());
			if (httpsUser == null) {
				LOG.warn(DFSConfigKeys.DFS_NAMENODE_KRB_HTTPS_USER_NAME_KEY
						+ " not defined in config. Starting http server as "
						+ SecurityUtil.getServerPrincipal(conf
								.get(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY),
								serverAddress.getHostName())
						+ ": Kerberized SSL may be not function correctly.");
			} else {
				// Kerberized SSL servers must be run from the host principal...
				LOG.info("Logging in as " + httpsUser
						+ " to start http server.");
				SecurityUtil.login(conf,
						DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY,
						DFSConfigKeys.DFS_NAMENODE_KRB_HTTPS_USER_NAME_KEY,
						infoSocAddr.getHostName());
			}
		}
		UserGroupInformation ugi = UserGroupInformation.getLoginUser();
		try {
			this.httpServer = ugi
					.doAs(new PrivilegedExceptionAction<HttpServer>() {
						@Override
						public HttpServer run() throws IOException,
								InterruptedException {
							String infoHost = infoSocAddr.getHostName();
							int infoPort = infoSocAddr.getPort();
							httpServer = new HttpServer("hdfs", infoHost,
									infoPort, infoPort == 0, conf, SecurityUtil
											.getAdminAcls(conf,
													DFSConfigKeys.DFS_ADMIN)) {
								{
									if (WebHdfsFileSystem.isEnabled(conf, LOG)) {
										// add SPNEGO authentication filter for
										// webhdfs
										final String name = "SPNEGO";
										final String classname = AuthFilter.class
												.getName();
										final String pathSpec = WebHdfsFileSystem.PATH_PREFIX
												+ "/*";
										Map<String, String> params = getAuthFilterParams(conf);
										defineFilter(webAppContext, name,
												classname, params,
												new String[] { pathSpec });
										LOG.info("Added filter '" + name
												+ "' (class=" + classname + ")");

										// add webhdfs packages
										addJerseyResourcePackage(
												NamenodeWebHdfsMethods.class
														.getPackage().getName()
														+ ";"
														+ Param.class
																.getPackage()
																.getName(),
												pathSpec);
									}
								}

								private Map<String, String> getAuthFilterParams(
										Configuration conf) throws IOException {
									Map<String, String> params = new HashMap<String, String>();
									String principalInConf = conf
											.get(DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL_KEY);
									if (principalInConf != null
											&& !principalInConf.isEmpty()) {
										params.put(
												DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL_KEY,
												SecurityUtil
														.getServerPrincipal(
																principalInConf,
																serverAddress
																		.getHostName()));
									}
									String httpKeytab = conf
											.get(DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_KEYTAB_KEY);
									if (httpKeytab != null
											&& !httpKeytab.isEmpty()) {
										params.put(
												DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_KEYTAB_KEY,
												httpKeytab);
									}
									return params;
								}
							};

							boolean certSSL = conf.getBoolean(
									"dfs.https.enable", false);
							boolean useKrb = UserGroupInformation
									.isSecurityEnabled();
							if (certSSL || useKrb) {
								boolean needClientAuth = conf.getBoolean(
										"dfs.https.need.client.auth", false);
								InetSocketAddress secInfoSocAddr = NetUtils
										.createSocketAddr(infoHost
												+ ":"
												+ conf.get("dfs.https.port",
														infoHost + ":" + 0));
								Configuration sslConf = new Configuration(false);
								if (certSSL) {
									sslConf.addResource(conf
											.get("dfs.https.server.keystore.resource",
													"ssl-server.xml"));
								}
								httpServer.addSslListener(secInfoSocAddr,
										sslConf, needClientAuth, useKrb);
								// assume same ssl port for all datanodes
								InetSocketAddress datanodeSslPort = NetUtils
										.createSocketAddr(conf.get(
												"dfs.datanode.https.address",
												infoHost + ":" + 50475));
								httpServer.setAttribute("datanode.https.port",
										datanodeSslPort.getPort());
							}
							httpServer.setAttribute("name.node", NameNode.this);
							httpServer.setAttribute("name.node.address",
									getNameNodeAddress());
							httpServer.setAttribute("name.system.image",
									getFSImage());
							httpServer.setAttribute(JspHelper.CURRENT_CONF,
									conf);
							httpServer.addInternalServlet("getDelegationToken",
									GetDelegationTokenServlet.PATH_SPEC,
									GetDelegationTokenServlet.class, true);
							httpServer.addInternalServlet(
									"renewDelegationToken",
									RenewDelegationTokenServlet.PATH_SPEC,
									RenewDelegationTokenServlet.class, true);
							httpServer.addInternalServlet(
									"cancelDelegationToken",
									CancelDelegationTokenServlet.PATH_SPEC,
									CancelDelegationTokenServlet.class, true);
							httpServer.addInternalServlet("fsck", "/fsck",
									FsckServlet.class, true);
							httpServer.addInternalServlet("getimage",
									"/getimage", GetImageServlet.class, true);
							httpServer.addInternalServlet("listPaths",
									"/listPaths/*", ListPathsServlet.class,
									false);
							httpServer.addInternalServlet("data", "/data/*",
									FileDataServlet.class, false);
							httpServer.addInternalServlet("checksum",
									"/fileChecksum/*",
									FileChecksumServlets.RedirectServlet.class,
									false);
							httpServer.addInternalServlet("contentSummary",
									"/contentSummary/*",
									ContentSummaryServlet.class, false);
							httpServer.start();

							// The web-server port can be ephemeral... ensure we
							// have the correct info
							infoPort = httpServer.getPort();
							httpAddress = new InetSocketAddress(infoHost,
									infoPort);
							conf.set("dfs.http.address", infoHost + ":"
									+ infoPort);
							LOG.info("Web-server up at: " + infoHost + ":"
									+ infoPort);
							return httpServer;
						}
					});
		} catch (InterruptedException e) {
			throw new IOException(e);
		} finally {
			if (UserGroupInformation.isSecurityEnabled()
					&& conf.get(DFSConfigKeys.DFS_NAMENODE_KRB_HTTPS_USER_NAME_KEY) != null) {
				// Go back to being the correct Namenode principal
				LOG.info("Logging back in as "
						+ SecurityUtil.getServerPrincipal(conf
								.get(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY),
								serverAddress.getHostName())
						+ " following http server start.");
				SecurityUtil.login(conf,
						DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY,
						DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY,
						serverAddress.getHostName());
			}
		}
	}

	/**
	 * Start NameNode.
	 * <p>
	 * The name-node can be started with one of the following startup options:
	 * <ul>
	 * <li>{@link StartupOption#REGULAR REGULAR} - normal name node startup</li>
	 * <li>{@link StartupOption#FORMAT FORMAT} - format name node</li>
	 * <li>{@link StartupOption#UPGRADE UPGRADE} - start the cluster upgrade and
	 * create a snapshot of the current file system state</li>
	 * <li>{@link StartupOption#ROLLBACK ROLLBACK} - roll the cluster back to
	 * the previous state</li>
	 * </ul>
	 * The option is passed via configuration field:
	 * <tt>dfs.namenode.startup</tt>
	 * 
	 * The conf will be modified to reflect the actual ports on which the
	 * NameNode is up and running if the user passes the port as
	 * <code>zero</code> in the conf.
	 * 
	 * @param conf
	 *            confirguration
	 * @throws IOException
	 */
	public NameNode(Configuration conf) throws IOException {
		try {
			initialize(conf);
		} catch (IOException e) {
			this.stop();
			throw e;
		}
	}

	/**
	 * Wait for service to finish. (Normally, it runs forever.)
	 */
	public void join() {
		try {
			this.server.join();
		} catch (InterruptedException ie) {
		}
	}

	/**
	 * Stop all NameNode threads and wait for all to finish.
	 */
	public void stop() {
		if (stopRequested)
			return;
		stopRequested = true;
		try {
			if (httpServer != null)
				httpServer.stop();
		} catch (Exception e) {
			LOG.error(StringUtils.stringifyException(e));
		}
		if (namesystem != null)
			namesystem.close();
		if (emptier != null)
			emptier.interrupt();
		if (server != null)
			server.stop();
		if (serviceRpcServer != null)
			serviceRpcServer.stop();
		if (myMetrics != null) {
			myMetrics.shutdown();
		}
		if (namesystem != null) {
			namesystem.shutdown();
		}
	}

	// ///////////////////////////////////////////////////
	// NamenodeProtocol
	// ///////////////////////////////////////////////////
	/**
	 * return a list of blocks & their locations on <code>datanode</code> whose
	 * total size is <code>size</code>
	 * 
	 * @param datanode
	 *            on which blocks are located
	 * @param size
	 *            total size of blocks
	 */
	public BlocksWithLocations getBlocks(DatanodeInfo datanode, long size)
			throws IOException {
		if (size <= 0) {
			throw new IllegalArgumentException("Unexpected not positive size: "
					+ size);
		}

		return namesystem.getBlocks(datanode, size);
	}

	// ///////////////////////////////////////////////////
	// ClientProtocol
	// ///////////////////////////////////////////////////

	public Token<DelegationTokenIdentifier> getDelegationToken(Text renewer)
			throws IOException {
		return namesystem.getDelegationToken(renewer);
	}

	@Override
	public long renewDelegationToken(Token<DelegationTokenIdentifier> token)
			throws InvalidToken, IOException {
		return namesystem.renewDelegationToken(token);
	}

	@Override
	public void cancelDelegationToken(Token<DelegationTokenIdentifier> token)
			throws IOException {
		namesystem.cancelDelegationToken(token);
	}

	@Override
	public PathPosition putObject(String objName) {
		PathPosition pp = null;
		try {
			pp = metaDataDb.put(objName);
		} catch (IOException e) {
			LOG.error("put object " + objName + " error");
		}
		return pp;
	}

	/**
	 * get object id
	 */
	@Override
	public long getObjectId(String objName) {
		long id = -1L;
		try {
			id = metaDataDb.getId(objName);
		} catch (IOException e) {
			LOG.error("get object " + objName + " id error");
		}
		return id;
	}

	/**
	 * get path + offset
	 */
	@Override
	public PathPosition getPathPosition(String objName) {
		PathPosition pp = null;
		try {
			pp = metaDataDb.getPathPosition(objName);
			//LOG.info(" Server name: " + objName + " id: " + getObjectId(objName) + " pp: " + pp);
		} catch (IOException e) {
			LOG.error("get object " + objName + " path position error");
		}
		return pp;
	}
	
	@Override
	public long deleteObject(String objName) {
		long id = -1L;
		try {
			id = metaDataDb.delete(objName);
		} catch (IOException e) {
			LOG.error("delete object " + objName + " path position error");
		}
		return id;
	}

	@Override
	public boolean exist(String objName) {
		return metaDataDb.exist(objName);
	}
	
	/**
	 * get all the objects(name and id)in hoss
	 * @return
	 */
	public Text listObjects(){
		Map<String, Long> objects = metaDataDb.listObjects();
		StringBuilder sb = new StringBuilder();
		if(objects != null){
			for(Entry<String, Long> entry: objects.entrySet()){
				sb.append(entry.getKey()).append("#").
				append(entry.getValue()).append("\t");
			}
		}
		String text = sb.toString().trim();
		return new Text(text);
	}
	
	/**
	 * get the top hottest object
	 * @param top
	 * @return
	 */
	public Text topHotObject(int top){
		List<HotObject> hotObjects = metaDataDb.topHotObject(top);
		StringBuilder sb = new StringBuilder();
		if(hotObjects != null){
			for(HotObject ho: hotObjects){
				sb.append(ho.getName()).append("#").
				append(ho.getHot()).append("\t");
			}
		}
		String text = sb.toString().trim();
		return new Text(text);
	}

	/** {@inheritDoc} */
	public LocatedBlocks getBlockLocations(String src, long offset, long length)
			throws IOException {
		myMetrics.incrNumGetBlockLocations();
		return namesystem.getBlockLocations(getClientMachine(), src, offset,
				length);
	}

	private static String getClientMachine() {
		String clientMachine = NamenodeWebHdfsMethods.getRemoteAddress();
		if (clientMachine == null) { // not a web client
			clientMachine = Server.getRemoteAddress();
		}
		if (clientMachine == null) { // not a RPC client
			clientMachine = "";
		}
		return clientMachine;
	}

	@Deprecated
	public void create(String src, FsPermission masked, String clientName,
			boolean overwrite, short replication, long blockSize)
			throws IOException {
		create(src, masked, clientName, overwrite, true, replication, blockSize);
	}

	/** {@inheritDoc} */
	public void create(String src, FsPermission masked, String clientName,
			boolean overwrite, boolean createParent, short replication,
			long blockSize) throws IOException {
		String clientMachine = getClientMachine();
		if (stateChangeLog.isDebugEnabled()) {
			stateChangeLog.debug("*DIR* NameNode.create: file " + src + " for "
					+ clientName + " at " + clientMachine);
		}
		if (!checkPathLength(src)) {
			throw new IOException("create: Pathname too long.  Limit "
					+ MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH
					+ " levels.");
		}
		namesystem.startFile(src, new PermissionStatus(UserGroupInformation
				.getCurrentUser().getShortUserName(), null, masked),
				clientName, clientMachine, overwrite, createParent,
				replication, blockSize);
		myMetrics.incrNumFilesCreated();
		myMetrics.incrNumCreateFileOps();
	}

	/** {@inheritDoc} */
	public LocatedBlock append(String src, String clientName)
			throws IOException {
		String clientMachine = getClientMachine();
		if (stateChangeLog.isDebugEnabled()) {
			stateChangeLog.debug("*DIR* NameNode.append: file " + src + " for "
					+ clientName + " at " + clientMachine);
		}
		LocatedBlock info = namesystem.appendFile(src, clientName,
				clientMachine);
		myMetrics.incrNumFilesAppended();
		return info;
	}

	/** {@inheritDoc} */
	public boolean recoverLease(String src, String clientName)
			throws IOException {
		String clientMachine = getClientMachine();
		return namesystem.recoverLease(src, clientName, clientMachine);
	}

	/** {@inheritDoc} */
	public boolean setReplication(String src, short replication)
			throws IOException {
		return namesystem.setReplication(src, replication);
	}

	/** {@inheritDoc} */
	public void setPermission(String src, FsPermission permissions)
			throws IOException {
		namesystem.setPermission(src, permissions);
	}

	/** {@inheritDoc} */
	public void setOwner(String src, String username, String groupname)
			throws IOException {
		namesystem.setOwner(src, username, groupname);
	}

	/**
	 * Stub for 0.20 clients that don't support HDFS-630
	 */
	public LocatedBlock addBlock(String src, String clientName)
			throws IOException {
		return addBlock(src, clientName, null);
	}

	public LocatedBlock addBlock(String src, String clientName,
			DatanodeInfo[] excludedNodes) throws IOException {

		List<Node> excludedNodeList = null;
		if (excludedNodes != null) {
			// We must copy here, since this list gets modified later on
			// in ReplicationTargetChooser
			excludedNodeList = new ArrayList<Node>(
					Arrays.<Node> asList(excludedNodes));
		}

		stateChangeLog.debug("*BLOCK* NameNode.addBlock: file " + src + " for "
				+ clientName);
		LocatedBlock locatedBlock = namesystem.getAdditionalBlock(src,
				clientName, excludedNodeList);
		if (locatedBlock != null)
			myMetrics.incrNumAddBlockOps();
		return locatedBlock;
	}

	/**
	 * The client needs to give up on the block.
	 */
	public void abandonBlock(Block b, String src, String holder)
			throws IOException {
		stateChangeLog.debug("*BLOCK* NameNode.abandonBlock: " + b
				+ " of file " + src);
		if (!namesystem.abandonBlock(b, src, holder)) {
			throw new IOException("Cannot abandon block during write to " + src);
		}
	}

	/** {@inheritDoc} */
	public boolean complete(String src, String clientName) throws IOException {
		stateChangeLog.debug("*DIR* NameNode.complete: " + src + " for "
				+ clientName);
		CompleteFileStatus returnCode = namesystem
				.completeFile(src, clientName);
		if (returnCode == CompleteFileStatus.STILL_WAITING) {
			return false;
		} else if (returnCode == CompleteFileStatus.COMPLETE_SUCCESS) {
			return true;
		} else {
			throw new IOException("Could not complete write to file " + src
					+ " by " + clientName);
		}
	}

	/**
	 * The client has detected an error on the specified located blocks and is
	 * reporting them to the server. For now, the namenode will mark the block
	 * as corrupt. In the future we might check the blocks are actually corrupt.
	 */
	public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
		stateChangeLog.info("*DIR* NameNode.reportBadBlocks");
		for (int i = 0; i < blocks.length; i++) {
			Block blk = blocks[i].getBlock();
			DatanodeInfo[] nodes = blocks[i].getLocations();
			for (int j = 0; j < nodes.length; j++) {
				DatanodeInfo dn = nodes[j];
				namesystem.markBlockAsCorrupt(blk, dn);
			}
		}
	}

	/** {@inheritDoc} */
	public long nextGenerationStamp(Block block, boolean fromNN)
			throws IOException {
		return namesystem.nextGenerationStampForBlock(block, fromNN);
	}

	/** {@inheritDoc} */
	public void commitBlockSynchronization(Block block,
			long newgenerationstamp, long newlength, boolean closeFile,
			boolean deleteblock, DatanodeID[] newtargets) throws IOException {
		namesystem.commitBlockSynchronization(block, newgenerationstamp,
				newlength, closeFile, deleteblock, newtargets);
	}

	public long getPreferredBlockSize(String filename) throws IOException {
		return namesystem.getPreferredBlockSize(filename);
	}

	/**
   */
	public boolean rename(String src, String dst) throws IOException {
		stateChangeLog.debug("*DIR* NameNode.rename: " + src + " to " + dst);
		if (!checkPathLength(dst)) {
			throw new IOException("rename: Pathname too long.  Limit "
					+ MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
		}
		boolean ret = namesystem.renameTo(src, dst);
		if (ret) {
			myMetrics.incrNumFilesRenamed();
		}
		return ret;
	}

	/**
   */
	@Deprecated
	public boolean delete(String src) throws IOException {
		return delete(src, true);
	}

	/** {@inheritDoc} */
	public boolean delete(String src, boolean recursive) throws IOException {
		if (stateChangeLog.isDebugEnabled()) {
			stateChangeLog.debug("*DIR* Namenode.delete: src=" + src
					+ ", recursive=" + recursive);
		}
		boolean ret = namesystem.delete(src, recursive);
		if (ret)
			myMetrics.incrNumDeleteFileOps();
		return ret;
	}

	/**
	 * Check path length does not exceed maximum. Returns true if length and
	 * depth are okay. Returns false if length is too long or depth is too
	 * great.
	 * 
	 */
	private boolean checkPathLength(String src) {
		Path srcPath = new Path(src);
		return (src.length() <= MAX_PATH_LENGTH && srcPath.depth() <= MAX_PATH_DEPTH);
	}

	/** {@inheritDoc} */
	public boolean mkdirs(String src, FsPermission masked) throws IOException {
		stateChangeLog.debug("*DIR* NameNode.mkdirs: " + src);
		if (!checkPathLength(src)) {
			throw new IOException("mkdirs: Pathname too long.  Limit "
					+ MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
		}
		return namesystem.mkdirs(src, new PermissionStatus(UserGroupInformation
				.getCurrentUser().getShortUserName(), null, masked));
	}

	/**
   */
	public void renewLease(String clientName) throws IOException {
		namesystem.renewLease(clientName);
	}

	@Override
	public DirectoryListing getListing(String src, byte[] startAfter)
			throws IOException {
		DirectoryListing files = namesystem.getListing(src, startAfter);
		myMetrics.incrNumGetListingOps();
		if (files != null) {
			myMetrics
					.incrNumFilesInGetListingOps(files.getPartialListing().length);
		}
		return files;
	}

	/**
	 * Get the file info for a specific file.
	 * 
	 * @param src
	 *            The string representation of the path to the file
	 * @throws IOException
	 *             if permission to access file is denied by the system
	 * @return object containing information regarding the file or null if file
	 *         not found
	 */
	public HdfsFileStatus getFileInfo(String src) throws IOException {
		myMetrics.incrNumFileInfoOps();
		return namesystem.getFileInfo(src);
	}

	/** @inheritDoc */
	public long[] getStats() throws IOException {
		return namesystem.getStats();
	}

	/**
   */
	public DatanodeInfo[] getDatanodeReport(DatanodeReportType type)
			throws IOException {
		DatanodeInfo results[] = namesystem.datanodeReport(type);
		if (results == null) {
			throw new IOException("Cannot find datanode report");
		}
		return results;
	}

	/**
	 * @inheritDoc
	 */
	public boolean setSafeMode(SafeModeAction action) throws IOException {
		return namesystem.setSafeMode(action);
	}

	/**
	 * Is the cluster currently in safe mode?
	 */
	public boolean isInSafeMode() {
		return namesystem.isInSafeMode();
	}

	/**
	 * @inheritDoc
	 */
	public void saveNamespace() throws IOException {
		namesystem.saveNamespace();
	}

	/**
	 * Refresh the list of datanodes that the namenode should allow to connect.
	 * Re-reads conf by creating new Configuration object and uses the files
	 * list in the configuration to update the list.
	 */
	public void refreshNodes() throws IOException {
		namesystem.refreshNodes(new Configuration());
	}

	/**
	 * Returns the size of the current edit log.
	 */
	public long getEditLogSize() throws IOException {
		return namesystem.getEditLogSize();
	}

	/**
	 * Roll the edit log.
	 */
	public CheckpointSignature rollEditLog() throws IOException {
		return namesystem.rollEditLog();
	}

	/**
	 * Roll the image
	 */
	public void rollFsImage() throws IOException {
		namesystem.rollFSImage();
	}

	public void finalizeUpgrade() throws IOException {
		namesystem.finalizeUpgrade();
	}

	public UpgradeStatusReport distributedUpgradeProgress(UpgradeAction action)
			throws IOException {
		return namesystem.distributedUpgradeProgress(action);
	}

	/**
	 * Dumps namenode state into specified file
	 */
	public void metaSave(String filename) throws IOException {
		namesystem.metaSave(filename);
	}

	/**
	 * Tell all datanodes to use a new, non-persistent bandwidth value for
	 * dfs.balance.bandwidthPerSec.
	 * 
	 * @param bandwidth
	 *            Blanacer bandwidth in bytes per second for all datanodes.
	 * @throws IOException
	 */
	public void setBalancerBandwidth(long bandwidth) throws IOException {
		namesystem.setBalancerBandwidth(bandwidth);
	}

	/** {@inheritDoc} */
	public ContentSummary getContentSummary(String path) throws IOException {
		return namesystem.getContentSummary(path);
	}

	/** {@inheritDoc} */
	public void setQuota(String path, long namespaceQuota, long diskspaceQuota)
			throws IOException {
		namesystem.setQuota(path, namespaceQuota, diskspaceQuota);
	}

	/** {@inheritDoc} */
	public void fsync(String src, String clientName) throws IOException {
		namesystem.fsync(src, clientName);
	}

	/** @inheritDoc */
	public void setTimes(String src, long mtime, long atime) throws IOException {
		namesystem.setTimes(src, mtime, atime);
	}

	// DatanodeProtocol
	public DatanodeRegistration register(DatanodeRegistration nodeReg)
			throws IOException {
		verifyVersion(nodeReg.getVersion());
		namesystem.registerDatanode(nodeReg);

		return nodeReg;
	}

	/**
	 * Data node notify the name node that it is alive Return an array of
	 * block-oriented commands for the datanode to execute. This will be either
	 * a transfer or a delete operation.
	 */
	public DatanodeCommand[] sendHeartbeat(DatanodeRegistration nodeReg,
			long capacity, long dfsUsed, long remaining, int xmitsInProgress,
			int xceiverCount) throws IOException {
		verifyRequest(nodeReg);
		return namesystem.handleHeartbeat(nodeReg, capacity, dfsUsed,
				remaining, xceiverCount, xmitsInProgress);
	}

	public DatanodeCommand blockReport(DatanodeRegistration nodeReg,
			long[] blocks) throws IOException {
		verifyRequest(nodeReg);
		BlockListAsLongs blist = new BlockListAsLongs(blocks);
		stateChangeLog.debug("*BLOCK* NameNode.blockReport: " + "from "
				+ nodeReg.getName() + " " + blist.getNumberOfBlocks() + " blocks");

		namesystem.processReport(nodeReg, blist);
		if (getFSImage().isUpgradeFinalized())
			return DatanodeCommand.FINALIZE;
		return null;
	}

	/**
	 * add new replica blocks to the Inode to target mapping also add the Inode
	 * file to DataNodeDesc
	 */
	public void blocksBeingWrittenReport(DatanodeRegistration nodeReg,
			long[] blocks) throws IOException {
		verifyRequest(nodeReg);
		BlockListAsLongs blist = new BlockListAsLongs(blocks);
		namesystem.processBlocksBeingWrittenReport(nodeReg, blist);

		stateChangeLog.info("*BLOCK* NameNode.blocksBeingWrittenReport: "
				+ "from " + nodeReg.getName() + " " + blist.getNumberOfBlocks() + " blocks");

	}

	public void blockReceived(DatanodeRegistration nodeReg, Block blocks[],
			String delHints[]) throws IOException {
		verifyRequest(nodeReg);
		stateChangeLog.debug("*BLOCK* NameNode.blockReceived: " + "from "
				+ nodeReg.getName() + " " + blocks.length + " blocks.");
		for (int i = 0; i < blocks.length; i++) {
			namesystem.blockReceived(nodeReg, blocks[i], delHints[i]);
		}
	}

	/** {@inheritDoc} */
	public ExportedBlockKeys getBlockKeys() throws IOException {
		return namesystem.getBlockKeys();
	}

	/**
   */
	public void errorReport(DatanodeRegistration nodeReg, int errorCode, String msg) throws IOException {
		// Log error message from datanode
		String dnName = (nodeReg == null ? "unknown DataNode" : nodeReg
				.getName());
		LOG.info("Error report from " + dnName + ": " + msg);
		if (errorCode == DatanodeProtocol.NOTIFY) {
			return;
		}
		verifyRequest(nodeReg);
		if (errorCode == DatanodeProtocol.DISK_ERROR) {
			LOG.warn("Volume failed on " + dnName);
		} else if (errorCode == DatanodeProtocol.FATAL_DISK_ERROR) {
			namesystem.removeDatanode(nodeReg);
		}
	}

	public NamespaceInfo versionRequest() throws IOException {
		return namesystem.getNamespaceInfo();
	}

	public UpgradeCommand processUpgradeCommand(UpgradeCommand comm) throws IOException {
		return namesystem.processDistributedUpgradeCommand(comm);
	}

	/**
	 * Verify request.
	 * 
	 * Verifies correctness of the datanode version, registration ID, and if the
	 * datanode does not need to be shutdown.
	 * 
	 * @param nodeReg
	 *            data node registration
	 * @throws IOException
	 */
	public void verifyRequest(DatanodeRegistration nodeReg) throws IOException {
		verifyVersion(nodeReg.getVersion());
		if (!namesystem.getRegistrationID().equals(nodeReg.getRegistrationID()))
			throw new UnregisteredDatanodeException(nodeReg);
	}

	/**
	 * Verify version.
	 * 
	 * @param version
	 * @throws IOException
	 */
	public void verifyVersion(int version) throws IOException {
		if (version != LAYOUT_VERSION)
			throw new IncorrectVersionException(version, "data node");
	}

	/**
	 * Returns the name of the fsImage file
	 */
	public File getFsImageName() throws IOException {
		return getFSImage().getFsImageName();
	}

	public FSImage getFSImage() {
		return namesystem.dir.fsImage;
	}

	/**
	 * Returns the name of the fsImage file uploaded by periodic checkpointing
	 */
	public File[] getFsImageNameCheckpoint() throws IOException {
		return getFSImage().getFsImageNameCheckpoint();
	}

	/**
	 * Returns the address on which the NameNodes is listening to.
	 * 
	 * @return the address on which the NameNodes is listening to.
	 */
	public InetSocketAddress getNameNodeAddress() {
		return serverAddress;
	}

	/**
	 * Returns the address of the NameNodes http server, which is used to access
	 * the name-node web UI.
	 * 
	 * @return the http address.
	 */
	public InetSocketAddress getHttpAddress() {
		return httpAddress;
	}

	NetworkTopology getNetworkTopology() {
		return this.namesystem.clusterMap;
	}

	/**
	 * Verify that configured directories exist, then Interactively confirm that
	 * formatting is desired for each existing directory and format them.
	 * 
	 * @param conf
	 * @param isConfirmationNeeded
	 * @return true if formatting was aborted, false otherwise
	 * @throws IOException
	 */
	private static boolean format(Configuration conf,
			boolean isConfirmationNeeded) throws IOException {
		Collection<File> dirsToFormat = FSNamesystem.getNamespaceDirs(conf);
		Collection<File> editDirsToFormat = FSNamesystem
				.getNamespaceEditsDirs(conf);
		for (Iterator<File> it = dirsToFormat.iterator(); it.hasNext();) {
			File curDir = it.next();
			if (!curDir.exists())
				continue;
			if (isConfirmationNeeded) {
				System.err.print("Re-format filesystem in " + curDir
						+ " ? (Y or N) ");
				if (!(System.in.read() == 'Y')) {
					System.err.println("Format aborted in " + curDir);
					return true;
				}
				while (System.in.read() != '\n')
					; // discard the enter-key
			}
		}

		FSNamesystem nsys = new FSNamesystem(new FSImage(dirsToFormat,
				editDirsToFormat), conf);
		nsys.dir.fsImage.format();
		return false;
	}

	private static boolean finalize(Configuration conf,
			boolean isConfirmationNeeded) throws IOException {
		Collection<File> dirsToFormat = FSNamesystem.getNamespaceDirs(conf);
		Collection<File> editDirsToFormat = FSNamesystem
				.getNamespaceEditsDirs(conf);
		FSNamesystem nsys = new FSNamesystem(new FSImage(dirsToFormat,
				editDirsToFormat), conf);
		System.err
				.print("\"finalize\" will remove the previous state of the files system.\n"
						+ "Recent upgrade will become permanent.\n"
						+ "Rollback option will not be available anymore.\n");
		if (isConfirmationNeeded) {
			System.err.print("Finalize filesystem state ? (Y or N) ");
			if (!(System.in.read() == 'Y')) {
				System.err.println("Finalize aborted.");
				return true;
			}
			while (System.in.read() != '\n')
				; // discard the enter-key
		}
		nsys.dir.fsImage.finalizeUpgrade();
		return false;
	}

	@Override
	public void refreshServiceAcl() throws IOException {
		if (!serviceAuthEnabled) {
			throw new AuthorizationException("Service Level Authorization not enabled!");
		}

		ServiceAuthorizationManager.refresh(new Configuration(), new HDFSPolicyProvider());
	}

	@Override
	public void refreshUserToGroupsMappings() throws IOException {
		LOG.info("Refreshing all user-to-groups mappings. Requested by user: " + UserGroupInformation.getCurrentUser().getShortUserName());
		Groups.getUserToGroupsMappingService().refresh();
	}

	@Override
	public void refreshSuperUserGroupsConfiguration() {
		LOG.info("Refreshing SuperUser proxy group mapping list ");

		ProxyUsers.refreshSuperUserGroupsConfiguration();
	}

	private static void printUsage() {
		System.err.println("Usage: java NameNode ["
				+ StartupOption.FORMAT.getName() + "] | ["
				+ StartupOption.UPGRADE.getName() + "] | ["
				+ StartupOption.ROLLBACK.getName() + "] | ["
				+ StartupOption.FINALIZE.getName() + "] | ["
				+ StartupOption.IMPORT.getName() + "]");
	}

	private static StartupOption parseArguments(String args[]) {
		int argsLen = (args == null) ? 0 : args.length;
		StartupOption startOpt = StartupOption.REGULAR;
		for (int i = 0; i < argsLen; i++) {
			String cmd = args[i];
			if (StartupOption.FORMAT.getName().equalsIgnoreCase(cmd)) {
				startOpt = StartupOption.FORMAT;
			} else if (StartupOption.REGULAR.getName().equalsIgnoreCase(cmd)) {
				startOpt = StartupOption.REGULAR;
			} else if (StartupOption.UPGRADE.getName().equalsIgnoreCase(cmd)) {
				startOpt = StartupOption.UPGRADE;
			} else if (StartupOption.ROLLBACK.getName().equalsIgnoreCase(cmd)) {
				startOpt = StartupOption.ROLLBACK;
			} else if (StartupOption.FINALIZE.getName().equalsIgnoreCase(cmd)) {
				startOpt = StartupOption.FINALIZE;
			} else if (StartupOption.IMPORT.getName().equalsIgnoreCase(cmd)) {
				startOpt = StartupOption.IMPORT;
			} else
				return null;
		}
		return startOpt;
	}

	private static void setStartupOption(Configuration conf, StartupOption opt) {
		conf.set("dfs.namenode.startup", opt.toString());
	}

	static StartupOption getStartupOption(Configuration conf) {
		return StartupOption.valueOf(conf.get("dfs.namenode.startup",
				StartupOption.REGULAR.toString()));
	}

	public static NameNode createNameNode(String argv[], Configuration conf)
			throws IOException {
		if (conf == null)
			conf = new Configuration();
		StartupOption startOpt = parseArguments(argv);
		if (startOpt == null) {
			printUsage();
			return null;
		}
		setStartupOption(conf, startOpt);

		switch (startOpt) {
		case FORMAT:
			boolean aborted = format(conf, true);
			System.exit(aborted ? 1 : 0);
		case FINALIZE:
			aborted = finalize(conf, true);
			System.exit(aborted ? 1 : 0);
		default:
		}
		DefaultMetricsSystem.initialize("NameNode");
		NameNode namenode = new NameNode(conf);
		LOG.info("Hoss MetaDataServer start...Zzzzzz");
		metaDataDb = new HosMetaData();
		if (metaDataDb != null) {
			LOG.info("load metadata from disk successfully.");
		} else {
			LOG.error("initlize hoss db fail.");
		}

		return namenode;
	}

	@Override
	public void run() {
		Configuration conf = new Configuration();
		int hours = conf.getInt("hoss.time.combinesmallfile", 2);
		LOG.info("hoss time combine small file:  " +
		                              conf.get("hoss.time.combinesmallfile"));
		while (true) {
			try {
				// 2 hours.
				TimeUnit.HOURS.sleep(hours);
				//TimeUnit.MINUTES.sleep(2);
				boolean combined = true;
				synchronized (metaDataDb) {
					SmallObjectsManager som  = new SmallObjectsManager(metaDataDb);
					combined = som.combine();
				}
				if(combined){
				   LOG.info("Combine small object successfully");
				}
			} catch (InterruptedException e) {
				LOG.error(StringUtils.stringifyException(e));
			}
		}
	}

	private void saveMetaData() {
		try {
			metaDataDb.saveMetaData();
		} catch (IOException e) {
			LOG.error("save meta data fail");
		}
	}

	private void addShutdownHook() {
		Thread hook = new Thread(new CloseOnJVMShutdown(this), "hos meta data shutdown hook");
		Runtime.getRuntime().addShutdownHook(hook);
	}

	private static class CloseOnJVMShutdown extends Thread {
		NameNode engine = null;

		@Override
		public void run() {
			engine.saveMetaData();
		}

		public CloseOnJVMShutdown(NameNode engine) {
			this.engine = engine;
		}
	}

	public static void main(String argv[]) throws Exception {
		try {
			StringUtils.startupShutdownMessage(NameNode.class, argv, LOG);
			NameNode namenode = createNameNode(argv, null);
			if (namenode != null){
				// add hook when hos server close
				namenode.addShutdownHook();
			}
			new Thread(namenode).start();

			namenode.join();
		} catch (Throwable e) {
			LOG.error(StringUtils.stringifyException(e));
			System.exit(-1);
		}
	}

}
