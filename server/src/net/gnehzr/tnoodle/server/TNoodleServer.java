package net.gnehzr.tnoodle.scrambles.server;

import static net.gnehzr.tnoodle.utils.Utils.fullyReadInputStream;
import static net.gnehzr.tnoodle.utils.Utils.GSON;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.Package;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.activation.MimetypesFileTypeMap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.gnehzr.tnoodle.scrambles.Scrambler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SuppressWarnings("restriction")
public class TNoodleServer {
	public static String NAME, VERSION;
	static {
		Package p = TNoodleServer.class.getPackage();

		NAME = p.getImplementationTitle();
		if(NAME == null) {
			NAME = TNoodleServer.class.getName();
		}
		VERSION = p.getImplementationVersion();
		if(VERSION == null) {
			VERSION = "devel";
		}
	}
	//TODO - it would be nice to kill threads when the tcp connection is killed, not sure if this is possible, though
	
	public TNoodleServer(int port, File scrambleFolder, boolean browse) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		
		SortedMap<String, Scrambler> scramblers = Scrambler.getScramblers(scrambleFolder);
		if(scramblers == null) {
			throw new IOException("Invalid directory: " + scrambleFolder.getAbsolutePath());
		}
		// TODO - check that the directories in www don't conflict with
		// import, scramble, view, or kill
		server.createContext("/", new FileHandler());
		server.createContext("/import/", new ImporterHandler());
		server.createContext("/scramble/", new ScrambleHandler(scramblers));
		server.createContext("/view/", new ScrambleViewHandler(scramblers));
		server.createContext("/kill/", new DeathHandler());
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		
		String addr = InetAddress.getLocalHost().getHostAddress() + ":" + port;
		System.out.println(NAME + "-" + VERSION + " started on " + addr);
		String url = "http://" + addr;
		//TODO - maybe it would make sense to open this url asap, that
		//       way the user's browser starts parsing tnt even as scramble
		//       plugins are being loaded
		if(browse) {
			if(Desktop.isDesktopSupported()) {
				Desktop d = Desktop.getDesktop();
				if(d.isSupported(Desktop.Action.BROWSE)) {
					try {
						URI uri = new URI(url);
						System.out.println("Opening " + uri + " in browser. Pass -n to disable this!");
						d.browse(uri);
						return;
					} catch(URISyntaxException e) {
						e.printStackTrace();
					}
				}
			}
			System.out.println("Sorry, it appears the Desktop api is not supported on your platform");
		}
		
		System.out.println("Visit " + url + " for a readme and demo.");
	}
	
	private class DeathHandler extends SafeHttpHandler {
		public DeathHandler() { }
		
		protected void wrappedHandle(HttpExchange t, String path[], HashMap<String, String> query) throws IOException {
			if(path.length == 2 && path[1].equals("now")) {
				// If localhost makes a request to
				// http://localhost:PORT/kill/now
				// that's enough for us to commit honorable suicide.
				String remote = t.getRemoteAddress().getAddress().getHostAddress();
				System.out.print("Asked to kill myself by " + remote + "...");
				if(remote.equals("127.0.0.1")) {
					// Only kill ourselves if someone on this machine requested it
					sendText(t, "Nice knowing ya'!");
					System.out.println("committing suicide");
					System.exit(0);
				}
				System.out.println("ignoring request");
			}
			sendText(t, NAME + "-" + VERSION);
		}
	}

	private class FileHandler extends SafeHttpHandler {
		MimetypesFileTypeMap mimes = new MimetypesFileTypeMap();
		{
			mimes.addMimeTypes("text/css css");
			mimes.addMimeTypes("text/html html htm");
			mimes.addMimeTypes("text/plain txt");
			
			mimes.addMimeTypes("image/png png");
			mimes.addMimeTypes("image/gif gif");
			mimes.addMimeTypes("image/vnd.microsoft.icon ico");

			mimes.addMimeTypes("application/x-font-ttf ttf");

			mimes.addMimeTypes("application/x-javascript js");
			mimes.addMimeTypes("application/json json");
			mimes.addMimeTypes("application/octet-stream *");
		}
		
		protected void wrappedHandle(HttpExchange t, String[] path, HashMap<String, String> query) throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			String fileName = t.getRequestURI().getPath().substring(1);
			if(fileName.isEmpty() || fileName.endsWith("/"))
				fileName += "index.html";
			else {
				// It's impossible to check if a URI (what getResource() returns) is a directory,
				// so we rely upon appending /index.html and checking if that path exists. If it does
				// we redirect the browser to the given path with a trailing / appended.
				boolean isDir = getClass().getResource("/www/" + fileName + "/index.html") != null;
				if(isDir) {
					sendTrailingSlashRedirect(t);
					return;
				}
			}
			InputStream is = getClass().getResourceAsStream("/www/" + fileName);
			if(is == null) {
				send404(t, fileName);
				return;
			}
			fullyReadInputStream(is, bytes);
			sendBytes(t, bytes, mimes.getContentType(fileName));
		}
	}
	
	private class ImporterHandler extends SafeHttpHandler {
		private final Pattern BOUNDARY_PATTERN = Pattern.compile("^.+boundary\\=(.+)$");
		@Override
		protected void wrappedHandle(HttpExchange t, String[] path, HashMap<String, String> query) throws Exception {
			if(t.getRequestMethod().equals("POST")) {
				// we assume this means we're uploading a file
				// the following isn't terribly robust, but it should work for us
				String boundary = t.getRequestHeaders().get("Content-Type").get(0);
				Matcher m = BOUNDARY_PATTERN.matcher(boundary);
				m.matches();
				boundary = "--" + m.group(1);
				
				BufferedReader in = new BufferedReader(new InputStreamReader(t.getRequestBody()));
				ArrayList<String> scrambles = new ArrayList<String>();
				String line;
				boolean finishedHeaders = false;
				while((line = in.readLine()) != null) {
					if(line.equals(boundary + "--"))
						break;
					if(finishedHeaders)
						scrambles.add(line);
					if(line.isEmpty()) //this indicates a CRLF CRLF
						finishedHeaders = true;
				}
				//we need to escape our backslashes
				String json = GSON.toJson(scrambles).replaceAll("\\\\", Matcher.quoteReplacement("\\\\"));
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				BufferedWriter html = new BufferedWriter(new OutputStreamWriter(bytes));
				html.append("<html><body><script>parent.postMessage('");
				html.append(json);
				html.append("', '*');</script></html>");
				html.close();
				sendHtml(t, bytes);
			} else {
				String urlStr = query.get("url");
				if(!urlStr.startsWith("http")) //might as well give it our best shot
					urlStr = "http://" + urlStr;
				URL url = new URL(urlStr);
				URLConnection conn = url.openConnection();
				ArrayList<String> scrambles = new ArrayList<String>();
				BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				String line;
				while((line = in.readLine()) != null) {
					scrambles.add(line);
				}
				sendJSON(t, GSON.toJson(scrambles), query.get("callback"));
			}
		}
	}
	


	/**
	 * @return A File representing the directory in which this program resides.
	 * If this is a jar file, this should be obvious, otherwise things are a little ambiguous.
	 */
	public static File getProgramDirectory() {
		File defaultScrambleFolder;
		try {
			defaultScrambleFolder = new File(TNoodleServer.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
		} catch (URISyntaxException e) {
			return new File(".");
		}
		if(defaultScrambleFolder.isFile()) //this should indicate a jar file
			defaultScrambleFolder = defaultScrambleFolder.getParentFile();
		return defaultScrambleFolder;
	}

	public static void main(String[] args) throws IOException {
		Launcher.wrapMain(args);

		OptionParser parser = new OptionParser();
		OptionSpec<Integer> portOpt = parser.acceptsAll(Arrays.asList("p", "port"), "The port to run the http server on").withOptionalArg().ofType(Integer.class).defaultsTo(80);
		OptionSpec<File> scrambleFolderOpt = parser.accepts("scramblers", "The directory of the scramble plugins").withOptionalArg().ofType(File.class).defaultsTo(new File(getProgramDirectory(), "scramblers"));
		OptionSpec<?> noBrowserOpt = parser.acceptsAll(Arrays.asList("n", "nobrowser"), "Don't open the browser when starting the server");
		OptionSpec<?> noUpgradeOpt = parser.acceptsAll(Arrays.asList("u", "noupgrade"), "If an instance of " + NAME + " is running on the desired port, kill it before starting up");
		OptionSpec<?> help = parser.acceptsAll(Arrays.asList("h", "help", "?"), "Show this help");
		try {
			OptionSet options = parser.parse(args);
			if(!options.has(help)) {
				int port = options.valueOf(portOpt);
				File scrambleFolder = options.valueOf(scrambleFolderOpt);
				boolean openBrowser = !options.has(noBrowserOpt);
				try {
					new TNoodleServer(port, scrambleFolder, openBrowser);
				} catch(BindException e) {
					// If this port is in use, we assume it's an instance of
					// TNoodleServer, and ask it to commit honorable suicide.
					// After that, we can start up. If it was a TNoodleServer,
					// it hopefully will have freed up the port we want.
					URL url = new URL("http://localhost:" + port + "/kill/now");
					System.out.println("Detected server running on port " + port + ", maybe it's an old " + NAME + "? Sending request to " + url + " to hopefully kill it.");
					URLConnection conn = url.openConnection();
					InputStream in = conn.getInputStream();
					in.close();
					// If we've gotten here, then the previous server may be dead,
					// lets try to start up.
					System.out.println("Hopefully the old server is now dead, trying to start up.");
					final int MAX_TRIES = 10;
					for(int i = 1; i <= MAX_TRIES; i++) {
						try {
							Thread.sleep(1000);
							System.out.println("Attempt " + i + "/" + MAX_TRIES + " to start up");
							new TNoodleServer(port, scrambleFolder, openBrowser);
							break;
						} catch(Exception ee) {
							ee.printStackTrace();
						}
					}
				}
				return;
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		parser.printHelpOn(System.out);
		System.exit(1); // non zero exit status
	}
}
