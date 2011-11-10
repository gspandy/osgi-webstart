/*
 * Copyright © 2011 jbundle.org. All rights reserved.
 */
package org.jbundle.util.osgi.jnlp;

import static java.util.jar.JarFile.MANIFEST_NAME;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SimpleTimeZone;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jbundle.util.osgi.ClassFinder;
import org.jbundle.util.osgi.ClassService;
import org.jbundle.util.osgi.finder.ClassFinderActivator;
import org.jbundle.util.osgi.finder.ClassServiceUtility;
import org.jbundle.util.webapp.osgi.OSGiFileServlet;
import org.jibx.runtime.BindingDirectory;
import org.jibx.runtime.IBindingFactory;
import org.jibx.runtime.IMarshallingContext;
import org.jibx.runtime.IUnmarshallingContext;
import org.jibx.runtime.JiBXException;
import org.jibx.schema.net.java.jnlp_6_0.AppletDesc;
import org.jibx.schema.net.java.jnlp_6_0.ApplicationDesc;
import org.jibx.schema.net.java.jnlp_6_0.Argument;
import org.jibx.schema.net.java.jnlp_6_0.Description;
import org.jibx.schema.net.java.jnlp_6_0.Description.Kind;
import org.jibx.schema.net.java.jnlp_6_0.Desktop;
import org.jibx.schema.net.java.jnlp_6_0.Homepage;
import org.jibx.schema.net.java.jnlp_6_0.Icon;
import org.jibx.schema.net.java.jnlp_6_0.Information;
import org.jibx.schema.net.java.jnlp_6_0.Jar;
import org.jibx.schema.net.java.jnlp_6_0.Jar.Download;
import org.jibx.schema.net.java.jnlp_6_0.Jar.Main;
import org.jibx.schema.net.java.jnlp_6_0.Java;
import org.jibx.schema.net.java.jnlp_6_0.Jnlp;
import org.jibx.schema.net.java.jnlp_6_0.Menu;
import org.jibx.schema.net.java.jnlp_6_0.OfflineAllowed;
import org.jibx.schema.net.java.jnlp_6_0.Param;
import org.jibx.schema.net.java.jnlp_6_0.Resources;
import org.jibx.schema.net.java.jnlp_6_0.Resources.Choice;
import org.jibx.schema.net.java.jnlp_6_0.Security;
import org.jibx.schema.net.java.jnlp_6_0.Shortcut;
import org.jibx.schema.net.java.jnlp_6_0.Shortcut.Online;
import org.jibx.schema.net.java.jnlp_6_0.Title;
import org.jibx.schema.net.java.jnlp_6_0.Vendor;
import org.jibx.schema.net.java.jnlp_6_0._Package;
import org.jibx.schema.net.java.jnlp_6_0._Package.Recursive;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;

/**
 * OSGi to Jnlp translation Servlet.
 * Note: Is it not required that this inherits from JnlpDownloadServlet,
 * I was hoping to using some of the code, but most of the useful stuff is private.
 * I do call JnlpDownloadServlet methods if I don't know what to do with the call.
 * Note: This is designed to override the JnlpDownloadServlet. I just a little 
 * apprehensive about the licensing if I wrap the (sun) code in an OSGi wrapper. 
 * @author don
 *
 */
public class OsgiJnlpServlet extends OSGiFileServlet /*JnlpDownloadServlet*/ {
	private static final long serialVersionUID = 1L;

    public static final String JNLP_MIME_TYPE = "application/x-java-jnlp-file";
    public final static String OUTPUT_ENCODING = "UTF-8";

    // Servlet params
    public static final String MAIN_CLASS = "mainClass";
    public static final String APPLET_CLASS = "appletClass";
    public static final String VERSION = "version";
    public static final String OTHER_PACKAGES = "otherPackages";
    public static final String TEMPLATE = "template";
    
    // Optional params
    public static final String TITLE = "title";
    public static final String VENDOR = "vendor";
    public static final String HOME_PAGE = "homePage";
    public static final String DESCRIPTION = "description";
    public static final String ICON = "icon";
    public static final String ONLINE = "online";
    public static final String DESKTOP = "desktop";
    public static final String MENU = "menu";
    public static final String JAVA_VERSION = "javaVersion";
    public static final String INITIAL_HEAP_SIZE = "initialHeapSize";
    public static final String MAX_HEAP_SIZE = "maxHeapSize";
    public static final String WIDTH = "width";
    public static final String HEIGHT = "height";
    public static final String INCLUDE = "include";
    public static final String EXCLUDE = "exclude";
    public static final String CODEBASE = "codebase";

    public static final String INCLUDE_DEFAULT = null;  // "org\\.jbundle\\..*|biz\\.source_code\\..*|com\\.tourapp\\..*";
    public static final String EXCLUDE_DEFAULT = "org\\.osgi\\..*";

    // Deploy param
    public static final String SERVICE_PID = "org.jbundle.util.osgi.jnlp";
    
    Date lastBundleChange = null;

    enum Changes {
        UNKNOWN,
        NONE,
        PARTIAL,
        ALL,
    };

    /**
     * Constructor.
     * @param context
     */
    public OsgiJnlpServlet() {
    	super();
    }
    
    /**
     * Constructor.
     * @param context
     */
    public OsgiJnlpServlet(Object context, String servicePid, Dictionary<String, String> properties) {
    	this();
    	init(context, servicePid, properties);
    }
    
    /**
     * Constructor.
     * @param context
     */
    public void init(Object context, String servicePid, Dictionary<String, String> properties) {
    	super.init(context, servicePid, properties);
    	
    	listener = new BundleChangeListener(this);
    	this.getBundleContext().addBundleListener(listener);
    }
    
    BundleChangeListener listener = null;
    public class BundleChangeListener implements BundleListener
    {
        OsgiJnlpServlet servlet = null;
        public BundleChangeListener(OsgiJnlpServlet servlet)
        {
            this.servlet = servlet;
        }
        @Override
        public void bundleChanged(BundleEvent event) {
            if (event.getType() == BundleEvent.UPDATED)
                servlet.lastBundleChange = new Date();   // Probably a better way to do this
        }
    }    
    /**
     * Free my resources.
     */
    public void free()
    {
        if (getBundleContext() != null)
            if (listener != null)
                getBundleContext().removeBundleListener(listener);
        super.free();
    }
    /**
     * Main entry point for a web get request.
     */
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

    	boolean fileFound = false;
    	if (isJnlp(request))
    		makeJnlp(request, response);
    	else
    	    fileFound = getDataFile(request, response);
    	if (!fileFound)
    	    fileFound = getResourceFile(request, response, false);
//        if (!fileFound)   // See JnlpDownloadServlet note
//    		super.doGet(request, response);
    }
    
    /**
     * Is this a url for jnlp?
     * @param request
     * @return
     */
    public boolean isJnlp(HttpServletRequest request)
    {
        if ((getRequestParam(request, MAIN_CLASS, null) != null) || (getRequestParam(request, APPLET_CLASS, null) != null))
            if (!request.getRequestURI().toUpperCase().endsWith(".HTML"))
                if (!request.getRequestURI().toUpperCase().endsWith(".HTM"))
                    return true;
        return false;
    }
    
    /**
     * Create the jnlp file give the main class name.
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    public void makeJnlp(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
    {
		ServletContext context = getServletContext();
		response.setContentType(JNLP_MIME_TYPE);
		
	    try {
			IBindingFactory jc = BindingDirectory.getFactory(Jnlp.class);

			Jnlp jnlp = null;
			
			String template = getRequestParam(request, TEMPLATE, null);
			if (template == null)
				jnlp = new Jnlp();
			else
			{
				URL url = context.getResource(template);
				InputStream inStream = url.openStream();
				
				IUnmarshallingContext unmarshaller = jc.createUnmarshallingContext();
				jnlp = (Jnlp)unmarshaller.unmarshalDocument(inStream, OUTPUT_ENCODING);
			}
			
			File jnlpFile = getJnlpFile(request);
			boolean forceScanBundle = !jnlpFile.exists();
			if (!forceScanBundle)
			    if (checkBundleChanges(request, response, jnlpFile))
			        return;   // Returned the cached jnlp or a cache up-to-date response
			Changes bundleChanged = setupJnlp(jnlp, request, forceScanBundle);
            if (bundleChanged == Changes.UNKNOWN)
            {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);   // Return a 'file not found' error
                return;
            }
			if (!forceScanBundle)
			    if (bundleChanged == Changes.PARTIAL)
			        setupJnlp(jnlp, request, true);  // Need to rescan everything
            if (bundleChanged == Changes.NONE)
            {   // Note: It may seem better to listen for bundle changes, but actually webstart uses the cached jnlp file
                if (checkCache(request, response, jnlpFile))
                    return;   // Returned the cached jnlp or a cache up-to-date response
            }
            // If bundleChanged == Changes.ALL need to return the new jnlp
			
            IMarshallingContext marshaller = jc.createMarshallingContext();
            marshaller.setIndent(4);

			Writer fileWriter = new FileWriter(jnlpFile);
            marshaller.marshalDocument(jnlp, OUTPUT_ENCODING, null, fileWriter);   // Cache jnlp
            fileWriter.close();
            Date lastModified = new Date(jnlpFile.lastModified());
            response.addHeader(LAST_MODIFIED, getHttpDate(lastModified));
            
            PrintWriter writer = response.getWriter();
            marshaller.marshalDocument(jnlp, OUTPUT_ENCODING, null, writer);
            lastBundleChange = lastModified;     // Use this cached file until bundles change
		} catch (JiBXException e) {
			e.printStackTrace();
		}
	}
    /**
     * If there have not been any bundle changes, return the cached jnlp file.
     * @param request
     * @param response
     * @return
     * @throws IOException
     */
    public boolean checkBundleChanges(HttpServletRequest request, HttpServletResponse response, File file) throws IOException
    {
        Date lastModified = new Date(file.lastModified());
        if ((lastBundleChange == null)
            || (lastBundleChange.after(lastModified)))
                return false;
        return checkCache(request, response, file);
    }
    /**
     * Return http response that the cache is up-to-date.
     * @param request
     * @param response
     * @return
     * @throws IOException
     */
    public boolean checkCache(HttpServletRequest request, HttpServletResponse response, File file) throws IOException
    {
        String requestIfModifiedSince = request.getHeader(IF_MODIFIED_SINCE);
        Date lastModified = new Date(file.lastModified());
        try {
            if(requestIfModifiedSince!=null){
                Date requestDate = getDateFromHttpDate(requestIfModifiedSince);
                if (file != null)
                    if (!requestDate.before(lastModified))
                    {   // Not modified since last time
                        response.setHeader(LAST_MODIFIED, request.getHeader(IF_MODIFIED_SINCE));
                        response.sendError(HttpServletResponse.SC_NOT_MODIFIED);
                        return true;    // Success - use your cached copy
                    }
            }
        } catch (ParseException e) {
            // Fall through
        }
        // If they want it again, send them my cached copy
        if ((file == null) || (!file.exists()))
            return false;   // Error - cache doesn't exist
        response.addHeader(LAST_MODIFIED, getHttpDate(lastModified));
        
        InputStream inStream = new FileInputStream(file);
        OutputStream writer = response.getOutputStream();
        copyStream(inStream, writer, true); // Ignore errors, as browsers do weird things
        inStream.close();
        writer.close();
        return true;    // Success - I returned the cached copy
    }
    /**
     * Get the jnlp cache file name.
     * @param request
     * @return
     */
    protected File getJnlpFile(HttpServletRequest request)
    {
        String query = getCodebase(request) + getHref(request) + '?' + request.getQueryString();
        String hash = Integer.toString(query.hashCode()).replace('-', 'a') + ".jnlp";
        return getBundleContext().getDataFile(hash);
    }
    /**
     * Populate the Jnlp xml.
     * @param jnlp
     * @param request
     * @param forceScanBundle Scan the bundle for package names even if the cache is current
     */
    protected Changes setupJnlp(Jnlp jnlp, HttpServletRequest request, boolean forceScanBundle)
    {
    	Set<Bundle> bundles = new HashSet<Bundle>();	// Bundle list

		String mainClass = getRequestParam(request, MAIN_CLASS, null);
		if (mainClass == null)
		    mainClass = getRequestParam(request, APPLET_CLASS, null);
		String version = getRequestParam(request, VERSION, null);
		String packageName = ClassFinderActivator.getPackageName(mainClass, false);
		Bundle bundle = findBundle(packageName, version);
		if (bundle == null)
			return Changes.UNKNOWN;

		jnlp.setCodebase(getCodebase(request));
		jnlp.setHref(getHref(request));
		
		setInformation(jnlp, bundle, request);
    	Security security = new Security();
    	jnlp.setSecurity(security);
				
		setJ2se(jnlp, bundle, request);
		
        String regexInclude = getRequestParam(request, INCLUDE, INCLUDE_DEFAULT);
        String regexExclude = getRequestParam(request, EXCLUDE, EXCLUDE_DEFAULT);
        String pathToJars = getPathToJars(request);

		Changes bundleChanged = Changes.UNKNOWN;
		bundleChanged = addBundle(jnlp, bundle, Main.TRUE, forceScanBundle, bundleChanged, pathToJars);
		isNewBundle(bundle, bundles);	// Add only once
		
		bundleChanged = addDependentBundles(jnlp, getBundleProperty(bundle, Constants.IMPORT_PACKAGE), bundles, forceScanBundle, bundleChanged, regexInclude, regexExclude, pathToJars);
		
		if (getRequestParam(request, OTHER_PACKAGES, null) != null)
		    bundleChanged = addDependentBundles(jnlp, getRequestParam(request, OTHER_PACKAGES, null).toString(), bundles, forceScanBundle, bundleChanged, regexInclude, regexExclude, pathToJars);
        
		if (getRequestParam(request, MAIN_CLASS, null) != null)
			setApplicationDesc(jnlp, mainClass, request);
		else
			setAppletDesc(jnlp, mainClass, bundle, request);
		return bundleChanged;
    }
    
    /**
     * Get the codebase from the request path.
     * @param request
     * @return
     */
    private String getCodebase(HttpServletRequest request)
    {
        String urlprefix = getUrlPrefix(request);
        String respath = request.getRequestURI();
        if (respath == null)
        	respath = "";
        String codebaseParam = getRequestParam(request, CODEBASE, null);
        int idx = respath.lastIndexOf('/');
        if (codebaseParam != null)
            if (respath.indexOf(codebaseParam) != -1)
                idx = respath.indexOf(codebaseParam) + codebaseParam.length() - 1;
        String codebase = respath.substring(0, idx + 1); // Include /
        codebase = urlprefix + request.getContextPath() + codebase;
        return codebase;
    }
    
    /**
     * Get the jnlp href from the request path.
     * @param request
     * @return
     */
    private String getHref(HttpServletRequest request)
    {
        String respath = request.getRequestURI();
        if (respath == null)
        	respath = "";
        String codebaseParam = getRequestParam(request, CODEBASE, null);
        int idx = respath.lastIndexOf('/');
        if (codebaseParam != null)
            if (respath.indexOf(codebaseParam) != -1)
                idx = respath.indexOf(codebaseParam) + codebaseParam.length() - 1;
        String href = respath.substring(idx + 1);    // Exclude /
        href = href + '?' + request.getQueryString();
        return href;
    }
    /**
     * Get the path to the jar files (root of context path if codebase specified).
     * @param request
     * @return
     */
    private String getPathToJars(HttpServletRequest request)
    {
        String codebaseParam = getRequestParam(request, CODEBASE, null);
        if ((codebaseParam == null) || (codebaseParam.length() == 0))
                return "";
        String pathToJars = request.getRequestURI();
        String path = request.getPathInfo();
        if (pathToJars.endsWith(path))
            pathToJars = pathToJars.substring(0, pathToJars.length() - path.length() + 1);  // Keep the trailing '/'
        int idx = pathToJars.indexOf(codebaseParam);
        if (idx != -1)
            pathToJars = pathToJars.substring(idx + 1);
        return pathToJars;
    }
    /**
     *  This code is heavily inspired by the stuff in HttpUtils.getRequestURL
     */
    private String getUrlPrefix(HttpServletRequest req) {
        StringBuffer url = new StringBuffer();
        String scheme = req.getScheme();
        int port = req.getServerPort();
        url.append(scheme);		// http, https
        url.append("://");
        url.append(req.getServerName());
        if ((scheme.equals("http") && port != 80)
	    || (scheme.equals("https") && port != 443)) {
            url.append(':');
            url.append(req.getServerPort());
        }
        return url.toString();
    }

    /**
     * Set up the jnlp information fields.
     * @param jnlp
     */
    public void setInformation(Jnlp jnlp, Bundle bundle, HttpServletRequest request)
	{
    	if (jnlp.getInformationList() == null)
    		jnlp.setInformationList(new ArrayList<Information>());
    	List<Information> informationList = jnlp.getInformationList();
    	if (informationList.size() == 0)
    		informationList.add(new Information());
    	Information information = informationList.get(0);
    	
    	Title title = new Title();
    	if (getRequestParam(request, TITLE, null) != null)
    		title.setTitle(getRequestParam(request, TITLE, null));
    	else if (getBundleProperty(bundle, Constants.BUNDLE_NAME) != null)
    		title.setTitle(getBundleProperty(bundle, Constants.BUNDLE_NAME));
    	else if (getBundleProperty(bundle, Constants.BUNDLE_SYMBOLICNAME) != null)
    		title.setTitle(getBundleProperty(bundle, Constants.BUNDLE_SYMBOLICNAME));
    	else
    		title.setTitle("Jnlp Application");
    	information.setTitle(title);
    	
    	Vendor vendor = new Vendor();
    	if (getRequestParam(request, VENDOR, null) != null)
        	vendor.setVendor(getRequestParam(request, VENDOR, null));
    	else if (getBundleProperty(bundle, Constants.BUNDLE_VENDOR) != null)
    		vendor.setVendor(getBundleProperty(bundle, Constants.BUNDLE_VENDOR));
    	else
    		vendor.setVendor("jbundle.org");
    	information.setVendor(vendor);
    	
    	Homepage homepage = new Homepage();
    	if (getRequestParam(request, HOME_PAGE, null) != null)
    		homepage.setHref(getRequestParam(request, HOME_PAGE, null));
    	else if (getBundleProperty(bundle, Constants.BUNDLE_DOCURL) != null)
    		homepage.setHref(getBundleProperty(bundle, Constants.BUNDLE_DOCURL));
    	else
    		homepage.setHref("http://www.jbundle.org");
    	information.setHomepage(homepage);
    	
    	if (information.getDescriptionList() == null)
    		information.setDescriptionList(new ArrayList<Description>());
    	if (information.getDescriptionList().size() == 0)
    	{
	    	Description description = new Description();
	    	description.setKind(Kind.ONELINE);
	    	if (getRequestParam(request, DESCRIPTION, null) != null)
	    		description.setString(getRequestParam(request, DESCRIPTION, null));
	    	else if (getBundleProperty(bundle, Constants.BUNDLE_DESCRIPTION) != null)
	    		description.setString(getBundleProperty(bundle, Constants.BUNDLE_DESCRIPTION));
	    	else
	    		description.setString("Jnlp Application");
	    	information.getDescriptionList().add(description);
    	}
    	
    	if (information.getIconList() == null)
    		information.setIconList(new ArrayList<Icon>());
    	if (information.getIconList().size() == 0)
    	{
	    	Icon icon = new Icon();
	    	icon.setHref(getRequestParam(request, ICON, getPathToJars(request) + "images/icons/jbundle32.jpg"));
	    	information.getIconList().add(icon);
    	}
    	
    	OfflineAllowed offlineAllowed = new OfflineAllowed();
    	information.setOfflineAllowed(offlineAllowed);
    	
    	Shortcut shortcut = new Shortcut();
    	if (Boolean.TRUE.toString().equalsIgnoreCase(getRequestParam(request, ONLINE, null)))
    		shortcut.setOnline(Online.TRUE);
    	else
    		shortcut.setOnline(Online.FALSE);	// Default
    	information.setShortcut(shortcut);
    	if (Boolean.TRUE.toString().equalsIgnoreCase(getRequestParam(request, DESKTOP, null)))
    	{
    		Desktop desktop = new Desktop();
    		shortcut.setDesktop(desktop);
    	}
    	String menuItem = getRequestParam(request, MENU, null);
    	if (menuItem != null)
    	{
	    	Menu menu = new Menu();
	    	menu.setSubmenu(menuItem);
	    	shortcut.setMenu(menu);
    	}
	}
    
    /**
     * Add the j2se lines.
     * @param jnlp
     */
    public void setJ2se(Jnlp jnlp, Bundle bundle, HttpServletRequest request)
	{
		Choice choice = getResource(jnlp, true);	// Clear the entries and create a new one
		Java java = new Java();
		choice.setJava(java);
		java.setVersion(getRequestParam(request, JAVA_VERSION, "1.6+"));
		if (getRequestParam(request, INITIAL_HEAP_SIZE, null) != null)
		    java.setInitialHeapSize(getRequestParam(request, INITIAL_HEAP_SIZE, null));
        if (getRequestParam(request, MAX_HEAP_SIZE, null) != null)
            java.setMaxHeapSize(getRequestParam(request, MAX_HEAP_SIZE, null));
	}
    
    /**
     * Call the osgi utility to find the bundle for this package and version.
     * @param packageName
     * @param version
     * @return
     */
	public Bundle findBundle(String packageName, String version)
	{
		ClassService classService = ClassServiceUtility.getClassService();
		if (classService == null)
			return null;	// Never
		ClassFinder classFinder = classService.getClassFinder(getBundleContext());
		if (classFinder == null)
			return null;
		Bundle bundle = classFinder.findBundle(null, getBundleContext(), packageName, version);
		if (bundle == null)
		{
	        Object resource = classFinder.deployThisResource(packageName, version, false);    // Deploy, but do not start the bundle
	        if (resource != null)
	        	bundle = classFinder.findBundle(resource, getBundleContext(), packageName, version);
		}
		return bundle;
	}
	
	/**
	 * Has the bundle been added yet?
	 * @param bundle
	 * @param bundles
	 * @return true If this bundle is not in the cache.
	 */
	public boolean isNewBundle(Bundle bundle, Set<Bundle> bundles)
	{
		if (bundle == null)
			return false;
		return bundles.add(bundle);
	}
	
	/**
	 * Add this bundle to the jnlp jar and package information.
	 * @param jnlp
	 * @param bundle
	 * @param main
	 * @param forceScanBundle Scan the bundle for package names even if the cache is current
	 * @return true if the bundle has changed from last time
	 */
	public Changes addBundle(Jnlp jnlp, Bundle bundle, Main main, boolean forceScanBundle, Changes bundleChanged, String pathToJars)
	{
		String name = getBundleProperty(bundle, Constants.BUNDLE_SYMBOLICNAME);
		String version = getBundleProperty(bundle, Constants.BUNDLE_VERSION);
		String activationPolicy = getBundleProperty(bundle, Constants.BUNDLE_ACTIVATIONPOLICY);
		Download download = Constants.ACTIVATION_LAZY.equalsIgnoreCase(activationPolicy) ? Download.LAZY : Download.EAGER;
		String filename = name + '-' + version + ".jar";
		String[] packages = moveBundleToJar(bundle, filename, forceScanBundle);
		if (packages == null) // No changes on this bundle
	        return (bundleChanged == Changes.NONE || bundleChanged == Changes.UNKNOWN) ? Changes.NONE : Changes.PARTIAL;
		if (main == null)
			main = Main.FALSE;
		if (pathToJars != null)
		    filename = pathToJars + filename;
		Jar jar = addJar(jnlp, filename, name, main, download);
		for (String packageName : packages)
		{
			addPackage(jnlp, jar, packageName, Recursive.FALSE);
		}
        return (bundleChanged == Changes.ALL || bundleChanged == Changes.UNKNOWN) ? Changes.ALL : Changes.PARTIAL;
	}
	
	/**
	 * Add all the dependent bundles (of this bundle) to the jar and package list.
	 * @param jnlp
	 * @param bundle
	 * @param bundles
	 * @param forceScanBundle Scan the bundle for package names even if the cache is current
     * @return true if the bundle has changed from last time
	 */
	public Changes addDependentBundles(Jnlp jnlp, String importPackage, Set<Bundle> bundles, boolean forceScanBundle, Changes bundleChanged, String regexInclude, String regexExclude, String pathToJars)
	{
		String[] packages = parseHeader(importPackage, regexInclude, regexExclude);
		for (String packageName : packages)
		{
			String properties[] = parseImport(packageName);
			String version = getVersion(properties);
			packageName = properties[0];
			Bundle subBundle = findBundle(packageName, version);
			if (isNewBundle(subBundle, bundles))
			{
				bundleChanged = addBundle(jnlp, subBundle, Main.FALSE, forceScanBundle, bundleChanged, pathToJars);
				bundleChanged = addDependentBundles(jnlp, getBundleProperty(subBundle, Constants.IMPORT_PACKAGE), bundles, forceScanBundle, bundleChanged, regexInclude, regexExclude, pathToJars);	// Recursive
			}
		}
		return bundleChanged;
	}
	
	/**
	 * Get this bundle header property.
	 * @param bundle
	 * @param property
	 * @return
	 */
	public static String getBundleProperty(Bundle bundle, String property)
	{
		return (String)bundle.getHeaders().get(property);
	}
	public static final String MANIFEST_DIR = "META-INF/";
	public static final String MANIFEST_PATH = MANIFEST_DIR + "MANIFEST.MF";
    public static int ONE_SEC_IN_MS = 1000;
	
	/**
	 * Create a jar for this bundle and move all the classes to the new jar.
	 * Note: I followed the same logic as in the java jar tool.
	 * @param bundle
	 * @param filename
	 * @param forceScanBundle Scan the bundle for package names even if the cache is current
	 * @return All the package names in the bundle or null if I am using the cached jar.
	 */
	public String[] moveBundleToJar(Bundle bundle, String filename, boolean forceScanBundle)
	{
        File fileOut = getBundleContext().getDataFile(filename);
        boolean createNewJar = true;
        if (fileOut.exists())
            if (bundle.getLastModified() <= (fileOut.lastModified() + ONE_SEC_IN_MS))   // File sys is usually accurate to sec 
            {
                createNewJar = false;
                if (!forceScanBundle)
                    return null;    // Use cached jar file
            }
        
        Set<String> packages = new HashSet<String>();
		try {
			Manifest manifest = null;
			String path = MANIFEST_PATH;
			URL url = bundle.getEntry(path);
			JarOutputStream zos = null;
			if (createNewJar)
			{
    			InputStream in = null;
    			if (url != null)
    			{
    				try {
    					in = url.openStream();
    				} catch (Exception e) {
    					e.printStackTrace();
    				}
    			}
    			if (in != null)
    			{
                    manifest = new Manifest(new BufferedInputStream(in));
                } else {
                    manifest = new Manifest();
                }
    			
    			FileOutputStream out = new FileOutputStream(fileOut);
    			
    	        zos = new JarOutputStream(out);
    	        if (manifest != null) {
    	            JarEntry e = new JarEntry(MANIFEST_DIR);
    	            e.setTime(System.currentTimeMillis());
    	            e.setSize(0);
    	            e.setCrc(0);
    	            zos.putNextEntry(e);
    	            e = new JarEntry(MANIFEST_NAME);
    	            e.setTime(System.currentTimeMillis());
    	            zos.putNextEntry(e);
    	            manifest.write(zos);
    	            zos.closeEntry();
    	        }
			}
			String paths = "/";
			String filePattern = "*";
			@SuppressWarnings("unchecked")
			Enumeration<URL> entries = bundle.findEntries(paths, filePattern, true);
			while (entries.hasMoreElements())
			{
				url = entries.nextElement();
				String name = url.getPath();
				if (name.startsWith("/"))
					name = name.substring(1);
    		    name = entryName(name);
    	        if (name.equals("") || name.equals("."))
    	            continue;
    	        if ((name.equalsIgnoreCase(MANIFEST_DIR)) || (name.equalsIgnoreCase(MANIFEST_PATH)))
            		continue;
    	        if (createNewJar)
    	        {
        	        boolean isDir = name.endsWith("/");
        	        long size = isDir ? 0 : -1; // ***????****  file.length();
        	        JarEntry e = new JarEntry(name);
        	        e.setTime(fileOut.lastModified()); //???
        	        if (size == 0) {
        	            e.setMethod(JarEntry.STORED);
        	            e.setSize(0);
        	            e.setCrc(0);
        	        }
        	        zos.putNextEntry(e);
        	        if (!isDir) {
        		        InputStream inStream = url.openStream();
        		        copyStream(inStream, zos, false);
        	            inStream.close();
        	        }
        	        zos.closeEntry();
    	        }
    	        
    	        if (!(name.toUpperCase().startsWith(MANIFEST_DIR)))
    	        		packages.add(getPackageFromName(name));
			}
			if (zos != null)
			    zos.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return packages.toArray(EMPTY_ARRAY);
	}
	/**
	 * Unpack bundle files and add them to the destination directory.
	 * @param bundle
	 * @param rootPathInJar
	 * @param destDir
	 */
	public static void transferBundleFiles(Bundle bundle, String rootPathInJar, String destDir)
	{
		if ((!destDir.endsWith("/")) && (!destDir.endsWith(File.separator)))
			destDir = destDir + File.separator;
	    Enumeration<?> paths = bundle.findEntries(rootPathInJar, "*", true);
	    if (paths != null)
	    {
	    	while (paths.hasMoreElements())
	    	{
				URL url = (URL)paths.nextElement();
				String fileName = url.getFile();
				if ((!fileName.endsWith("/")) && (!fileName.endsWith(File.separator)))
				{
					int startLocalPath = fileName.indexOf(rootPathInJar) + rootPathInJar.length();
					if (startLocalPath > 0)
					{	// Always
						fileName = fileName.substring(startLocalPath);
						if ((fileName.startsWith("/")) || (fileName.startsWith(File.separator)))
							fileName = fileName.substring(1);
						fileName = destDir + fileName;
	                	File file = new File(fileName);
	                	file = file.getParentFile();
	                	if (!file.exists())
	                		file.mkdirs();
						try {
							FileOutputStream outStream = new FileOutputStream(fileName);
							copyStream(url.openStream(), outStream, false);
						} catch (FileNotFoundException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
	    	}
	    }
	}

	/**
	 * Very similar to the code in jar tool.
	 * @param name
	 * @return
	 */
    private String entryName(String name) {
        name = name.replace(File.separatorChar, '/');
        String matchPath = "";
        /* Need to add code to consolidate paths
        for (String path : paths) {
            if (name.startsWith(path)
                && (path.length() > matchPath.length())) {
                matchPath = path;
            }
        }
        */
        name = name.substring(matchPath.length());

        if (name.startsWith("/")) {
            name = name.substring(1);
        } else if (name.startsWith("./")) {
            name = name.substring(2);
        }
        return name;
    }
	public static final String[] EMPTY_ARRAY = new String[0];
	
	/**
	 * Add jar information to jnlp.
	 * @param jnlp
	 * @param href
	 * @param part
	 * @param main
	 * @param download
	 * @return
	 */
    public Jar addJar(Jnlp jnlp, String href, String part, Main main, Download download)
    {
    	if (main == null)
    		main = Main.FALSE;
    	if (download == null)
    		download = Download.LAZY;
		Choice choice = getResource(jnlp, false);
		Jar jar = new Jar();
		choice.setJar(jar);
		jar.setHref(href);
		jar.setPart(part);
		jar.setDownload(download);
		jar.setMain(main);
		return jar;
    }
    
    /**
     * Add package jnlp entry.
     * @param jnlp
     * @param jar
     * @param packagePath
     * @param recursive
     * @return
     */
    public _Package addPackage(Jnlp jnlp, Jar jar, String packagePath, Recursive recursive)
    {
		Choice choice = getResource(jnlp, false);
		_Package pack = new _Package();
		choice.setPackage(pack);
		pack.setPart(jar.getPart());
		pack.setName(packagePath);
		if (recursive == null)
			recursive = Recursive.FALSE;
		pack.setRecursive(recursive);
		return pack;
    }
    
    /**
     * Set jnlp application description.
     * @param jnlp
     * @param mainClass
     */
    public void setApplicationDesc(Jnlp jnlp, String mainClass, HttpServletRequest request)
    {
    	if (jnlp.getApplicationDesc() == null)
    		jnlp.setApplicationDesc(new ApplicationDesc());
    	ApplicationDesc applicationDesc = jnlp.getApplicationDesc();
    	applicationDesc.setMainClass(mainClass);
    	
    	List<Argument> arguments = applicationDesc.getArgumentList();
    	if (arguments == null)
    	    applicationDesc.setArgumentList(arguments = new ArrayList<Argument>());
    	@SuppressWarnings("unchecked")
        Enumeration<String> names = request.getParameterNames();
    	while (names.hasMoreElements())
    	{
    	    String name = names.nextElement();
            if (isServletParam(name))
                continue;
    	    String value = request.getParameter(name);
    	    if (value != null)
    	        name = name + "=" + value;
        	Argument argument = new Argument();
        	argument.setArgument(name);
        	arguments.add(argument);
    	}
    }
    
    /**
     * Set jnlp applet description.
     * @param jnlp
     * @param mainClass
     */
    public void setAppletDesc(Jnlp jnlp, String mainClass, Bundle bundle, HttpServletRequest request)
    {
        String appletName = null;
        if (getRequestParam(request, TITLE, null) != null)
            appletName = getRequestParam(request, TITLE, null);
        else if (getBundleProperty(bundle, Constants.BUNDLE_NAME) != null)
            appletName = getBundleProperty(bundle, Constants.BUNDLE_NAME);
        else if (getBundleProperty(bundle, Constants.BUNDLE_SYMBOLICNAME) != null)
            appletName = getBundleProperty(bundle, Constants.BUNDLE_SYMBOLICNAME);
        else
            appletName = "Jnlp Application";
    	if (jnlp.getAppletDesc() == null)
    		jnlp.setAppletDesc(new AppletDesc());
    	AppletDesc appletDesc = jnlp.getAppletDesc();
    	appletDesc.setMainClass(mainClass);
    	appletDesc.setName(appletName);
    	appletDesc.setWidth(getRequestParam(request, WIDTH, "350"));
    	appletDesc.setHeight(getRequestParam(request, HEIGHT, "600"));
        
        List<Param> params = appletDesc.getParamList();
        if (params == null)
            appletDesc.setParamList(params = new ArrayList<Param>());
        @SuppressWarnings("unchecked")
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements())
        {
            String name = names.nextElement();
            if (isServletParam(name))
                continue;
            String value = request.getParameter(name);
            Param argument = new Param();
            argument.setName(name);
            if (value != null)
                argument.setValue(value);
            params.add(argument);
        }
    }

    /**
     * Is this a servlet param (that I should not pass to the applications)?
     * @param name
     * @return
     */
    private boolean isServletParam(String name)
    {
        if (name == null)
            return false;
        if (name.startsWith(SERVICE_PID))
            return true;
        return false;
    }
    
    /**
     * Create a new resource entry.
     * @param jnlp
     * @return
     */
    protected Choice getResource(Jnlp jnlp, boolean firstTime)
    {
		if (jnlp.getResourceList() == null)
			jnlp.setResourceList(new ArrayList<Resources>());
		List<Resources> resourcesList = jnlp.getResourceList();
		if (resourcesList.size() == 0)
			resourcesList.add(new Resources());
		Resources resources = resourcesList.get(0);
		List<Choice> choiceList = resources.getChoiceList();
		
		if (firstTime)
		for (int i = choiceList.size() - 1; i >= 0; i--)
		{
			choiceList.remove(i);
		}
		
		Choice choice = new Choice();
		choiceList.add(choice);
		return choice;    	
    }

    /**
     * Jnlp is asking for the jar file that I just created, return it.
     * @param request
     * @param response
     * @return
     * @throws IOException
     */
    public boolean getDataFile(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
    	String path = request.getPathInfo();
    	if (path == null)
    		return false;
    	path = path.substring(path.lastIndexOf("/") + 1);  // Jars are placed at the root of the cache directory

    	File file = getBundleContext().getDataFile(path);
    	if ((file == null) || (!file.exists()))
    	{  // Don't return a 404, try to read the file using JnlpDownloadServlet
//            response.sendError(HttpServletResponse.SC_NOT_FOUND);   // Return a 'file not found' error
    		return false;
    	}
    	return this.checkCache(request, response, file);
    }

	/**
	 * Get the package name from the jar entry path.
	 * @param name
	 * @return
	 */
	public static String getPackageFromName(String name)
	{
		if (name.lastIndexOf('/') != -1)
			name = name.substring(0, name.lastIndexOf('/'));
		if (name.startsWith("/"))
			name = name.substring(1);
		return name.replace('/', '.');		
	}
	
	/**
	 * Get the version number from the import properties.
	 * @param properties
	 * @return
	 */
    public static String getVersion(String[] properties)
    {
		if (properties.length > 0)
		{
			for (String property : properties)
			{
				if (property.startsWith(Constants.VERSION_ATTRIBUTE))
				{
					String[] props = property.split("\\ |\\[|\\]|\\(|\\)|\\=|\\\"");
					for (int i = 1; i < props.length; i++)
					{
						if (props[i].length() > 0)
							return props[i];
					}
				}
			}
		}
    	return null;
    }
    
    /**
     * Split the import properties.
     * @param value
     * @return
     */
    static public String[] parseImport(String value) {
    	return value.split(";");
    }
    
    /**
     * Split the import header properties.
     * @param value
     * @return
     */
    static public String[] parseHeader(String value, String regexInclude, String regexExclude) {

        if (value == null)
    		return EMPTY_ARRAY;
    	String[] properties = value.split(",");
    	for (int i = 0; i < properties.length; i++)
    	{
    		if (properties[i].indexOf(Constants.VERSION_ATTRIBUTE + "=") != -1)
    		{	// Version may have been split because it has spaces
    			for (int j = i + 1; j < properties.length; j++)
    			{
    	    		if (!properties[j].endsWith("\""))
    	    			break;
	    			properties[i] = properties[i] + "," + properties[j];	// Version	
	    			properties[j] = "";
    			}
    		}
    		if (regexExclude != null)
    		    if (properties[i].matches(regexExclude))
    		        properties[i] = "";
            if (regexInclude != null)
                if (!properties[i].matches(regexInclude))
                    properties[i] = "";
    	}
    	return properties;
    }

    private static SimpleDateFormat httpDateFormat = null;
    static {
        httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        httpDateFormat.setCalendar(Calendar.getInstance(new SimpleTimeZone(0, "GMT")));
    }
     
    public synchronized static String getHttpDate(Date date){
    return httpDateFormat.format(date);
    }
     
    public synchronized static Date getDateFromHttpDate(String date) throws ParseException{
    return httpDateFormat.parse(date);
    }
    public static final String IF_MODIFIED_SINCE = "If-Modified-Since";
    public static final String LAST_MODIFIED = "Last-Modified";
     
    /**
     * Get the properties for this OSGi service.
     * @return The properties.
     */
    public Dictionary<String,String> getDictionary()
    {
        return null;
    }
    /**
     * Convenience method.
     * Note: You will have to cast the class or override this in your actual OSGi servlet.
     */
    public BundleContext getBundleContext()
    {
        return (BundleContext)super.getBundleContext();
    }
}
