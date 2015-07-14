/* 書画骨董 private-stream consumer.
 */

package com.thomsonreuters.Shogakotto;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.net.*;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.bridge.SLF4JBridgeHandler;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.reuters.rfa.common.Context;
import com.reuters.rfa.common.DeactivatedException;
import com.reuters.rfa.common.Dispatchable;
import com.reuters.rfa.common.DispatchException;
import com.reuters.rfa.common.EventQueue;
import com.reuters.rfa.common.Handle;
import com.reuters.rfa.config.ConfigDb;
import com.reuters.rfa.session.Session;

public class Shogakotto {

/* Application configuration. */
	private Config config;

/* RFA context. */
	private Rfa rfa;

/* RFA asynchronous event queue. */
	private EventQueue event_queue;

/* RFA consumer */
	private AnalyticConsumer consumer;

/* Analytic list. */
	private List<AnalyticStream> streams;

	private static Logger LOG = LogManager.getLogger (Shogakotto.class.getName());
	private static Logger RFA_LOG = LogManager.getLogger ("com.reuters.rfa");

	private static final String RSSL_PROTOCOL		= "rssl";
	private static final String SSLED_PROTOCOL		= "ssled";

	private static final String SERVER_LIST_PARAM		= "server-list";
	private static final String APPLICATION_ID_PARAM	= "application-id";
	private static final String INSTANCE_ID_PARAM		= "instance-id";
	private static final String POSITION_PARAM		= "position";
	private static final String DICTIONARY_PARAM		= "dictionary";
	private static final String RETRY_TIMER_PARAM		= "retry-timer";
	private static final String RETRY_LIMIT_PARAM		= "retry-limit";
	private static final String UUID_PARAM			= "uuid";

	private static final String SESSION_OPTION		= "session";
	private static final String SYMBOL_PATH_OPTION		= "symbol-path";
	private static final String HELP_OPTION			= "help";
	private static final String VERSION_OPTION		= "version";

	private static final String SESSION_NAME		= "Session";
	private static final String CONNECTION_NAME		= "Connection";
	private static final String CONSUMER_NAME		= "Consumer";

	private static Options buildOptions() {
		Options opts = new Options();

		Option help = OptionBuilder.withLongOpt (HELP_OPTION)
					.withDescription ("print this message")
					.create ("h");
		opts.addOption (help);

		Option version = OptionBuilder.withLongOpt (VERSION_OPTION)
					.withDescription ("print version information and exit")
					.create();
		opts.addOption (version);

		Option session = OptionBuilder.hasArg()
					.isRequired()
					.withArgName ("uri")
					.withDescription ("TREP-RT session declaration")
					.withLongOpt (SESSION_OPTION)
					.create();
		opts.addOption (session);

		Option symbol_path = OptionBuilder.hasArg()
					.isRequired()
					.withArgName ("file")
					.withDescription ("read from symbol path")
					.withLongOpt (SYMBOL_PATH_OPTION)
					.create();
		opts.addOption (symbol_path);

		return opts;
	}

	private static void printHelp (Options options) {
		new HelpFormatter().printHelp ("Shogakotto", options);
	}

	private static Map<String, String> parseQuery (String query) throws UnsupportedEncodingException {
		final Map<String, String> query_pairs = new LinkedHashMap<String, String>();
		if (!Strings.isNullOrEmpty (query)) {
			final String[] pairs = query.split ("&");
			for (String pair : pairs) {
				int idx = pair.indexOf ("=");
				query_pairs.put (URLDecoder.decode (pair.substring (0, idx), "UTF-8"),
					URLDecoder.decode (pair.substring (idx + 1), "UTF-8"));
			}
		}
		return query_pairs;
	}

	private void init (CommandLine line, Options options) throws Exception {
		if (line.hasOption (HELP_OPTION)) {
			printHelp (options);
			return;
		}

/* Configuration. */
		this.config = new Config();

		if (line.hasOption (SESSION_OPTION)) {
			final String session = line.getOptionValue (SESSION_OPTION);
			List<SessionConfig> session_configs = new ArrayList<SessionConfig>();
			if (!Strings.isNullOrEmpty (session)) {
				LOG.debug ("Session declaration: {}", session);
				final URI parsed = new URI (session);
/* For each key-value pair, i.e. ?a=x&b=y&c=z -> (a,x) (b,y) (c,z) */
				final ImmutableMap<String, String> query = ImmutableMap.copyOf (parseQuery (parsed.getQuery()));

/* Extract out required parameters */
				final String protocol = parsed.getScheme();
				final String server_list = query.get (SERVER_LIST_PARAM);
				String[] servers = { parsed.getHost() };
/* Override host in URL with server-list query parameter */
				if (!Strings.isNullOrEmpty (server_list)) {
					servers = Iterables.toArray (Splitter.on (',')
							.trimResults()
							.omitEmptyStrings()
							.split (server_list), String.class);
				}

/* Minimum parameters to construct session configuration */
				SessionConfig session_config = new SessionConfig (SESSION_NAME, CONNECTION_NAME, CONSUMER_NAME, protocol, servers);

/* Optional session parameters: */
				if (!Strings.isNullOrEmpty (parsed.getUserInfo()))
					session_config.setUserName (parsed.getUserInfo());
/* -1 if the port is undefined */
				if (-1 != parsed.getPort()) 
					session_config.setDefaultPort (Integer.toString (parsed.getPort()));
/* Catch default URL of host/ as empty */
				if (!Strings.isNullOrEmpty (parsed.getPath())
					&& parsed.getPath().length() > 1)
				{
					session_config.setServiceName (new File (parsed.getPath()).getName());
				}
				if (query.containsKey (APPLICATION_ID_PARAM))
					session_config.setApplicationId (query.get (APPLICATION_ID_PARAM));
				if (query.containsKey (INSTANCE_ID_PARAM))
					session_config.setInstanceId (query.get (INSTANCE_ID_PARAM));
				if (query.containsKey (POSITION_PARAM))
					session_config.setPosition (query.get (POSITION_PARAM));
				if (query.containsKey (DICTIONARY_PARAM)) {
					Iterator<String> it = Splitter.on (',')
									.trimResults()
									.limit (2)
									.split (query.get (DICTIONARY_PARAM)).iterator();
					if (it.hasNext())
						session_config.setFieldDictionary (it.next());
					if (it.hasNext())
						session_config.setEnumDictionary (it.next());
				}
				if (query.containsKey (RETRY_TIMER_PARAM))
					session_config.setRetryTimer (query.get (RETRY_TIMER_PARAM));
				if (query.containsKey (RETRY_LIMIT_PARAM))
					session_config.setRetryLimit (query.get (RETRY_LIMIT_PARAM));
				if (query.containsKey (UUID_PARAM))
					session_config.setUuid (query.get (UUID_PARAM));

				LOG.debug ("Session evaluation: {}", session_config.toString());
				session_configs.add (session_config);
			}
			if (!session_configs.isEmpty()) {
				final SessionConfig[] array = session_configs.toArray (new SessionConfig[session_configs.size()]);
				this.config.setSessions (array);
			}
		}

/* Symbol list. */
		List<Analytic> analytics = new ArrayList<Analytic> ();

		if (line.hasOption (SYMBOL_PATH_OPTION)) {
			this.config.setSymbolPath (line.getOptionValue (SYMBOL_PATH_OPTION));
			File symbol_path = new File (this.config.getSymbolPath());
			if (symbol_path.canRead()) {
				Scanner line_scanner = new Scanner (symbol_path);
/* Without resorting to Commons CSV ... */
				Pattern regex = Pattern.compile (",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
				int line_number = 0;	// for error notices
				try {
					while (line_scanner.hasNextLine()) {
						++line_number;
						Scanner field_scanner = new Scanner (line_scanner.nextLine());
						field_scanner.useDelimiter (regex);
						String service, app_name, query, symbol_name;
						if (!field_scanner.hasNext())
							throw new IOException ("Missing service field in analytic file \"" + this.config.getSymbolPath() + "\" line " + line_number + ".");
						service = field_scanner.next().replaceAll ("^[\"]+|[\"]+$", "");
						if (!field_scanner.hasNext())
							throw new IOException ("Missing app name field in analytic file \"" + this.config.getSymbolPath() + "\" line " + line_number + ".");
						app_name = field_scanner.next().replaceAll ("^[\"]+|[\"]+$", "");
						if (!field_scanner.hasNext())
							throw new IOException ("Missing query field in analytic file \"" + this.config.getSymbolPath() + "\" line " + line_number + ".");
						query = field_scanner.next().replaceAll ("^[\"]+|[\"]+$", "");
						if (!field_scanner.hasNext())
							throw new IOException ("Missing symbol name field in analytic file \"" + this.config.getSymbolPath() + "\" line " + line_number + ".");
						symbol_name = field_scanner.next().replaceAll ("^[\"]+|[\"]+$", "");
						if (!query.isEmpty()) {
							Analytic new_analytic = new Analytic (service, app_name, query, symbol_name);
							analytics.add (new_analytic);
							LOG.trace ("symbol: {}", new_analytic);
						}
					}
				} finally {
					line_scanner.close();
				}
				LOG.debug ("Read {} symbols from {}.", analytics.size(), symbol_path);
			}
		}

		LOG.debug (this.config.toString());

/* RFA Logging. */
// Remove existing handlers attached to j.u.l root logger
		SLF4JBridgeHandler.removeHandlersForRootLogger();
// add SLF4JBridgeHandler to j.u.l's root logger
		SLF4JBridgeHandler.install();

		if (RFA_LOG.isDebugEnabled()) {
			java.util.logging.Logger rfa_logger = java.util.logging.Logger.getLogger ("com.reuters.rfa");
			rfa_logger.setLevel (java.util.logging.Level.FINE);
		}

/* RFA Context. */
		this.rfa = new Rfa (this.config);
		this.rfa.init();

/* RFA asynchronous event queue. */
		this.event_queue = EventQueue.create (this.config.getEventQueueName());

/* RFA consumer */
		this.consumer = new AnalyticConsumer (this.config.getSession(),
					this.rfa,
					this.event_queue);
		this.consumer.init();

/* Create state for subscribed RIC. */
		this.streams = new ArrayList<AnalyticStream> (analytics.size());
		for (Analytic analytic : analytics) {
			AnalyticStream stream = new AnalyticStream();
			this.consumer.createAnalyticStream (analytic, stream);
			this.streams.add (stream);
			LOG.trace (analytic.toString());
		}

	}

/* LOG4J2 logging is terminated by an installed shutdown hook.  This hook can
 * disabled by adding shutdownHook="disable" to the <Configuration> stanza.
 */
	private class ShutdownThread extends Thread {
		private Shogakotto app;
		private org.apache.logging.log4j.core.LoggerContext context;
		public ShutdownThread (Shogakotto app) {
			this.app = app;
/* Capture on startup as we cannot capture on shutdown as it would try to reinit:
 *   WARN Unable to register shutdown hook due to JVM state
 */
			this.context = (org.apache.logging.log4j.core.LoggerContext)LogManager.getContext();
		}
		@Override
		public void run() {
			if (null != this.app
				&& null != this.app.event_queue
				&& this.app.event_queue.isActive())
			{
				this.app.event_queue.deactivate();
				try {
					LOG.trace ("Waiting for mainloop shutdown ...");
					while (!this.app.is_shutdown) {
						Thread.sleep (100);
					}
					LOG.trace ("Shutdown complete.");
				} catch (InterruptedException e) {}
			}
/* LOG4J2-318 to manually shutdown.
 */
			if (context.isStarted()
				&& !context.getConfiguration().isShutdownHookEnabled())
			{
				LOG.trace ("Shutdown log4j2.");
				context.stop();
			}
		}
	}

	private void run (CommandLine line, Options options) throws Exception {
		this.init (line, options);
		Thread shutdown_hook = new ShutdownThread (this);
		Runtime.getRuntime().addShutdownHook (shutdown_hook);
		LOG.trace ("Shutdown hook installed.");
		this.mainloop();
		LOG.trace ("Shutdown in progress.");
/* Cannot remove hook if shutdown is in progress. */
//		Runtime.getRuntime().removeShutdownHook (shutdown_hook);
//		LOG.trace ("Removed shutdown hook.");
		this.clear();
		this.is_shutdown = true;
	}

	public volatile boolean is_shutdown = false;

	private void drainqueue() {
		LOG.trace ("Draining event queue.");
		int count = 0;
		try {
			while (this.event_queue.dispatch (Dispatchable.NO_WAIT) > 0) { ++count; }
			LOG.trace ("Queue contained {} events.", count);
		} catch (DeactivatedException e) {
/* ignore on empty queue */
			if (count > 0) LOG.catching (e);
		} catch (Exception e) {
			LOG.catching (e);
		}
	}

	private void mainloop() {
		try {
			while (this.event_queue.isActive()) {
				this.event_queue.dispatch (Dispatchable.INFINITE_WAIT);
			}
		} catch (DeactivatedException e) {
/* manual shutdown */
			LOG.trace ("Mainloop deactivated.");
		} catch (Throwable t) {
			LOG.catching (t);
		} finally {
			if (!this.event_queue.isActive()) this.event_queue.deactivate();
			this.drainqueue();
		}
	}

	private void clear() {
/* Prevent new events being generated whilst shutting down. */
		if (null != this.event_queue && this.event_queue.isActive()) {
			LOG.trace ("Deactivating EventQueue.");
			this.event_queue.deactivate();
			this.drainqueue();
		}

		if (null != this.consumer) {
			LOG.trace ("Closing Consumer.");
			this.consumer.clear();
			this.consumer = null;
		}

		if (null != this.event_queue) {
			LOG.trace ("Closing EventQueue.");
			this.event_queue.destroy();
			this.event_queue = null;
		}

		if (null != this.rfa) {
			LOG.trace ("Closing RFA.");
			this.rfa.clear();
			this.rfa = null;
		}
	}

	public static void main (String[] args) throws Exception {
		final Options options = Shogakotto.buildOptions();
		CommandLine line = null;
		try {
			line = new PosixParser().parse (options, args);
		} catch (ParseException e) {
			Shogakotto.printHelp (options);
			System.exit (1);
		}
		Shogakotto app = new Shogakotto();
		app.run (line, options);
	}
}

/* eof */
