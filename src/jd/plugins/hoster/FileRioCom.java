//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.utils.locale.JDL;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "filerio.in", "filerio.com", "filekeen.com" }, urls = { "http://(www\\.)?(filerio\\.in|filekeen\\.com|filerio\\.com)/[a-z0-9]{12}", "vSGzhkIKEfRUhbUNUSED_REGEXfdgrtjRET36fdfjhtwe85t7459zghwghior", "vSGzhkIKEfRUhbUNUSED_REGEXfdgdadadrtjRET36fdfjhtwe85t7459zghwghior1" }) 
public class FileRioCom extends PluginForHost {

    private final static String SSL_CONNECTION      = "SSL_CONNECTION";

    private String              correctedBR         = "";
    private static final String PASSWORDTEXT        = "(<br><b>Password:</b> <input|<br><b>Passwort:</b> <input)";
    private static final String COOKIE_HOST         = "http://filerio.in";
    private static final String MAINTENANCE         = ">This server is in maintenance mode";
    private static final String MAINTENANCEUSERTEXT = "This server is under Maintenance";
    private static final String ALLWAIT_SHORT       = "Waiting till new downloads can be started";
    private static Object       LOCK                = new Object();

    // XfileSharingProBasic Version 2.5.4.6, modified fnf-texts
    @Override
    public void correctDownloadLink(DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("https://", "http://").replaceAll("(filerio|filekeen)\\.com/", "filerio.in/"));
    }

    @Override
    public String rewriteHost(String host) {
        if ("filerio.com".equals(getHost()) || "filekeen.com".equals(getHost())) {
            if (host == null || "filerio.com".equals(host) || "filekeen.com".equals(host)) {
                return "filerio.in";
            }
        }
        return super.rewriteHost(host);
    }

    @Override
    public String getAGBLink() {
        return COOKIE_HOST + "/tos.html";
    }

    public FileRioCom(PluginWrapper wrapper) {
        super(wrapper);
        if ("filerio.in".equals(getHost())) {
            this.enablePremium(COOKIE_HOST + "/premium.html");
            this.setConfigElements();
        }
    }

    // do not add @Override here to keep 0.* compatibility
    public boolean hasAutoCaptcha() {
        return false;
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        correctDownloadLink(link);
        br.setFollowRedirects(false);
        br.setCookie(COOKIE_HOST, "lang", "english");
        getPage(link.getDownloadURL());
        if (new Regex(correctedBR, Pattern.compile("((;|>)File Not Found(<|\\&)|file has been expired due to inactivity|file has been removed by its owner|>File has been removed due to Copyright|>File has been removed)", Pattern.CASE_INSENSITIVE)).matches()) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (correctedBR.contains(MAINTENANCE)) {
            link.getLinkStatus().setStatusText(JDL.L("plugins.hoster.xfilesharingprobasic.undermaintenance", MAINTENANCEUSERTEXT));
            return AvailableStatus.TRUE;
        }
        String filename = new Regex(correctedBR, "You have requested.*?https?://(www\\.)?" + this.getHost() + "/[A-Za-z0-9]{12}/(.*?)</font>").getMatch(1);
        if (filename == null) {
            filename = new Regex(correctedBR, "fname\"( type=\"hidden\")? value=\"(.*?)\"").getMatch(1);
            if (filename == null) {
                filename = new Regex(correctedBR, "<h2>Download File(.*?)</h2>").getMatch(0);
                if (filename == null) {
                    filename = new Regex(correctedBR, "(?i)(File)?name ?:? ?(<[^>]+> ?)+?([^<>\"\\']+)").getMatch(2);
                }
            }
        }
        String filesize = new Regex(correctedBR, "\\(([0-9]+ bytes)\\)").getMatch(0);
        if (filesize == null) {
            filesize = new Regex(correctedBR, "</font>[ ]+\\(([^<>\"\\'/]+)\\)(.*?)</font>").getMatch(0);
        }
        if (filename == null || filename.equals("")) {
            if (correctedBR.contains("You have reached the download-limit")) {
                logger.warning("Waittime detected, please reconnect to make the linkchecker work!");
                return AvailableStatus.UNCHECKABLE;
            }
            logger.warning("The filename equals null, throwing \"plugin defect\" now...");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        filename = filename.replaceAll("(</b>|<b>|\\.html)", "");
        link.setProperty("plainfilename", filename);
        link.setFinalFileName(filename.trim());
        if (filesize != null && !filesize.equals("")) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        doFree(downloadLink, true, -2, "freelink");
    }

    public void doFree(DownloadLink downloadLink, boolean resumable, int maxchunks, String directlinkproperty) throws Exception, PluginException {
        String passCode = null;
        String md5hash = new Regex(correctedBR, "<b>MD5.*?</b>.*?nowrap>(.*?)<").getMatch(0);
        if (md5hash != null) {
            md5hash = md5hash.trim();
            logger.info("Found md5hash: " + md5hash);
            downloadLink.setMD5Hash(md5hash);
        }

        String dllink = checkDirectLink(downloadLink, directlinkproperty);
        /**
         * Video links can already be found here, if a link is found here we can skip wait times and captchas
         */
        if (dllink == null) {
            checkErrors(downloadLink, false, passCode);
            if (correctedBR.contains("\"download1\"")) {
                postPage(br.getURL(), "op=download1&usr_login=&id=" + new Regex(downloadLink.getDownloadURL(), "/([A-Za-z0-9]{12})$").getMatch(0) + "&fname=" + Encoding.urlEncode(downloadLink.getStringProperty("plainfilename")) + "&referer=&method_free=Free+Download");
                checkErrors(downloadLink, false, passCode);
            }
            dllink = getDllink();
        }
        if (dllink == null) {
            Form dlForm = br.getFormbyProperty("name", "F1");
            if (dlForm == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dlForm.remove(null);
            final long timeBefore = System.currentTimeMillis();
            boolean password = false;
            boolean skipWaittime = false;
            if (new Regex(correctedBR, PASSWORDTEXT).matches()) {
                password = true;
                logger.info("The downloadlink seems to be password protected.");
            }

            /* Captcha START */
            if (correctedBR.contains(";background:#ccc;text-align")) {
                logger.info("Detected captcha method \"plaintext captchas\" for this host");
                /** Captcha method by ManiacMansion */
                String[][] letters = new Regex(Encoding.htmlDecode(br.toString()), "<span style=\\'position:absolute;padding\\-left:(\\d+)px;padding\\-top:\\d+px;\\'>(\\d)</span>").getMatches();
                if (letters == null || letters.length == 0) {
                    logger.warning("plaintext captchahandling broken!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                SortedMap<Integer, String> capMap = new TreeMap<Integer, String>();
                for (String[] letter : letters) {
                    capMap.put(Integer.parseInt(letter[0]), letter[1]);
                }
                StringBuilder code = new StringBuilder();
                for (String value : capMap.values()) {
                    code.append(value);
                }
                dlForm.put("code", code.toString());
                logger.info("Put captchacode " + code.toString() + " obtained by captcha metod \"plaintext captchas\" in the form.");
            } else if (correctedBR.contains("/captchas/")) {
                logger.info("Detected captcha method \"Standard captcha\" for this host");
                String[] sitelinks = HTMLParser.getHttpLinks(br.toString(), null);
                String captchaurl = null;
                if (sitelinks == null || sitelinks.length == 0) {
                    logger.warning("Standard captcha captchahandling broken!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                for (String link : sitelinks) {
                    if (link.contains("/captchas/")) {
                        captchaurl = link;
                        break;
                    }
                }
                if (captchaurl == null) {
                    logger.warning("Standard captcha captchahandling broken!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                String code = getCaptchaCode("xfilesharingprobasic", captchaurl, downloadLink);
                dlForm.put("code", code);
                logger.info("Put captchacode " + code + " obtained by captcha metod \"Standard captcha\" in the form.");
            } else if (new Regex(correctedBR, "(api\\.recaptcha\\.net|google\\.com/recaptcha/api/)").matches()) {
                logger.info("Detected captcha method \"Re Captcha\" for this host");
                final Recaptcha rc = new Recaptcha(br, this);
                rc.setForm(dlForm);
                String id = new Regex(correctedBR, "\\?k=([A-Za-z0-9%_\\+\\- ]+)\"").getMatch(0);
                rc.setId(id);
                rc.load();
                File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                String c = getCaptchaCode("recaptcha", cf, downloadLink);
                Form rcform = rc.getForm();
                rcform.put("recaptcha_challenge_field", rc.getChallenge());
                rcform.put("recaptcha_response_field", Encoding.urlEncode(c));
                logger.info("Put captchacode " + c + " obtained by captcha metod \"Re Captcha\" in the form and submitted it.");
                dlForm = rc.getForm();
                /** wait time is often skippable for reCaptcha handling */
                skipWaittime = true;
            }
            /* Captcha END */
            if (password) {
                passCode = handlePassword(passCode, dlForm, downloadLink);
            }
            if (!skipWaittime) {
                waitTime(timeBefore, downloadLink);
            }
            sendForm(dlForm);
            logger.info("Submitted DLForm");
            checkErrors(downloadLink, true, passCode);
            dllink = getDllink();
            if (dllink == null) {
                logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        logger.info("Final downloadlink = " + dllink + " starting the download...");
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            correctBR();
            checkServerErrors();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setProperty(directlinkproperty, dllink);
        if (passCode != null) {
            downloadLink.setProperty("pass", passCode);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    /** Remove HTML code which could break the plugin */
    public void correctBR() throws NumberFormatException, PluginException {
        correctedBR = br.toString();
        ArrayList<String> someStuff = new ArrayList<String>();
        ArrayList<String> regexStuff = new ArrayList<String>();
        regexStuff.add("<\\!(\\-\\-.*?\\-\\-)>");
        regexStuff.add("(display: none;\">.*?</div>)");
        regexStuff.add("(visibility:hidden>.*?<)");
        for (String aRegex : regexStuff) {
            String lolz[] = br.getRegex(aRegex).getColumn(0);
            if (lolz != null) {
                for (String dingdang : lolz) {
                    someStuff.add(dingdang);
                }
            }
        }
        for (String fun : someStuff) {
            correctedBR = correctedBR.replace(fun, "");
        }
    }

    public String getDllink() {
        String dllink = br.getRedirectLocation();
        if (dllink == null) {
            if (dllink == null) {
                dllink = new Regex(correctedBR, "Download: <a href=\"(.*?)\"").getMatch(0);
                if (dllink == null) {
                    dllink = new Regex(correctedBR, "\"(http://(\\d+\\.\\d+\\.\\d+\\.\\d+|([a-z0-9]+\\.)filerio\\.in):\\d+/d/[a-z0-9]+/[^<>\"]*?)\"").getMatch(0);
                    if (dllink == null) {
                        dllink = new Regex(correctedBR, "<span style=\"width:915px; height:67px; font\\-family:\"Tahoma\"; font\\-size:12px; color:#356186; border:1px solid #bbb; padding:7px;\">[\t\n\r ]+<a href=\"(http://[^<>\"]*?)\"").getMatch(0);
                        if (dllink == null) {
                            // Try to find crypted link
                            dllink = br.getRegex(">eval\\(unescape\\(\\'([^<>\"]*?)\\'\\)\\)").getMatch(0);
                            if (dllink != null) {
                                dllink = new Regex(Encoding.htmlDecode(dllink), "location\\.href=\"(http://[^<>\"]*?)\"").getMatch(0);
                            }
                            if (dllink == null) {
                                String cryptedScripts[] = new Regex(correctedBR, "p\\}\\((.*?)\\.split\\('\\|'\\)").getColumn(0);
                                if (cryptedScripts != null && cryptedScripts.length != 0) {
                                    for (String crypted : cryptedScripts) {
                                        dllink = decodeDownloadLink(crypted);
                                        if (dllink != null) {
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return dllink;
    }

    private void getPage(String page) throws Exception {
        page = fixLinkSSL(page);
        br.getPage(page);
        correctBR();
    }

    private void postPage(String page, String postdata) throws Exception {
        page = fixLinkSSL(page);
        br.postPage(page, postdata);
        correctBR();
    }

    private void sendForm(Form form) throws Exception {
        br.submitForm(form);
        correctBR();
    }

    public void checkErrors(DownloadLink theLink, boolean checkAll, String passCode) throws NumberFormatException, PluginException {
        if (checkAll) {
            if (new Regex(correctedBR, PASSWORDTEXT).matches() || correctedBR.contains("Wrong password")) {
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            if (correctedBR.contains("Wrong captcha")) {
                logger.warning("Wrong captcha or wrong password!");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            if (correctedBR.contains("\">Skipped countdown<")) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "Fatal countdown error (countdown skipped)");
            }
        }
        /** Wait time reconnect handling */
        if (new Regex(correctedBR, "(You have reached the download\\-limit|You have to wait)").matches()) {
            /** TODO: Improve those regexes */
            String tmphrs = new Regex(correctedBR, "\\s+(\\d+)\\s+hours?").getMatch(0);
            if (tmphrs == null) {
                tmphrs = new Regex(correctedBR, "You have to wait.*?\\s+(\\d+)\\s+hours?").getMatch(0);
            }
            String tmpmin = new Regex(correctedBR, "\\s+(\\d+)\\s+minutes?").getMatch(0);
            if (tmpmin == null) {
                tmpmin = new Regex(correctedBR, "You have to wait.*?\\s+(\\d+)\\s+minutes?").getMatch(0);
            }
            String tmpsec = new Regex(correctedBR, "\\s+(\\d+)\\s+seconds?").getMatch(0);
            if (tmphrs == null && tmpmin == null && tmpsec == null) {
                logger.info("Waittime regexes seem to be broken");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
            } else {
                int minutes = 0, seconds = 0, hours = 0;
                if (tmphrs != null) {
                    hours = Integer.parseInt(tmphrs);
                }
                if (tmpmin != null) {
                    minutes = Integer.parseInt(tmpmin);
                }
                if (tmpsec != null) {
                    seconds = Integer.parseInt(tmpsec);
                }
                int waittime = (3600 * hours + (60 * minutes) + seconds + 1) * 1000;
                logger.info("Detected waittime #2, waiting " + waittime + "milliseconds");
                /** Not enough wait time to reconnect->Wait and try again */
                if (waittime < 180000) {
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.xfilesharingprobasic.allwait", ALLWAIT_SHORT), waittime);
                }
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            }
        }
        if (correctedBR.contains("You're using all download slots for IP")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
        }
        if (correctedBR.contains("Error happened when generating Download Link")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error!", 10 * 60 * 1000l);
        }
        /** Error handling for only-premium links */
        if (new Regex(correctedBR, "( can download files up to |Upgrade your account to download bigger files|>Upgrade your account to download larger files|>The file You requested  reached max downloads limit for Free Users|Please Buy Premium To download this file<|This file reached max downloads limit)").matches()) {
            String filesizelimit = new Regex(correctedBR, "You can download files up to(.*?)only").getMatch(0);
            if (filesizelimit != null) {
                filesizelimit = filesizelimit.trim();
                logger.warning("As free user you can download files up to " + filesizelimit + " only");
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, "Free users can only download files up to " + filesizelimit);
            } else {
                logger.warning("Only downloadable via premium");
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, "Only downloadable via premium or registered");
            }
        }
        if (correctedBR.contains(MAINTENANCE)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.xfilesharingprobasic.undermaintenance", MAINTENANCEUSERTEXT), 2 * 60 * 60 * 1000l);
        }
    }

    public void checkServerErrors() throws NumberFormatException, PluginException {
        if (new Regex(correctedBR, Pattern.compile("No file", Pattern.CASE_INSENSITIVE)).matches()) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Server error");
        }
        if (new Regex(correctedBR, "(File Not Found|<h1>404 Not Found</h1>)").matches()) {
            logger.warning("Server says link offline, please recheck that!");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
    }

    private String decodeDownloadLink(String s) {
        String decoded = null;

        try {
            Regex params = new Regex(s, "\\'(.*?[^\\\\])\\',(\\d+),(\\d+),\\'(.*?)\\'");

            String p = params.getMatch(0).replaceAll("\\\\", "");
            int a = Integer.parseInt(params.getMatch(1));
            int c = Integer.parseInt(params.getMatch(2));
            String[] k = params.getMatch(3).split("\\|");

            while (c != 0) {
                c--;
                if (k[c].length() != 0) {
                    p = p.replaceAll("\\b" + Integer.toString(c, a) + "\\b", k[c]);
                }
            }

            decoded = p;
        } catch (Exception e) {
        }

        String finallink = null;
        if (decoded != null) {
            finallink = new Regex(decoded, "name=\"src\"value=\"(.*?)\"").getMatch(0);
            if (finallink == null) {
                finallink = new Regex(decoded, "type=\"video/divx\"src=\"(.*?)\"").getMatch(0);
                if (finallink == null) {
                    finallink = new Regex(decoded, "\\.addVariable\\(\\'file\\',\\'(http://.*?)\\'\\)").getMatch(0);
                }
            }
        }
        return finallink;
    }

    private String handlePassword(String passCode, Form pwform, DownloadLink thelink) throws IOException, PluginException {
        passCode = thelink.getStringProperty("pass", null);
        if (passCode == null) {
            passCode = Plugin.getUserInput("Password?", thelink);
        }
        pwform.put("password", passCode);
        logger.info("Put password \"" + passCode + "\" entered by user in the DLForm.");
        return Encoding.urlEncode(passCode);
    }

    private String checkDirectLink(DownloadLink downloadLink, String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                Browser br2 = br.cloneBrowser();
                URLConnectionAdapter con = null;
                try {
                    con = br2.openGetConnection(dllink);
                    if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                        downloadLink.setProperty(property, Property.NULL);
                        dllink = null;
                    }
                } finally {
                    try {
                        con.disconnect();
                    } catch (final Throwable e) {
                    }
                }
            } catch (Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        try {
            login(account, true);
        } catch (PluginException e) {
            account.setValid(false);
            return ai;
        }
        String space = br.getRegex(Pattern.compile("<td>Used space:</td>.*?<td.*?b>([0-9\\.]+) of [0-9\\.]+ (Mb|GB)</b>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)).getMatch(0);
        if (space != null) {
            ai.setUsedSpace(space.trim() + " Mb");
        }
        account.setValid(true);
        String availabletraffic = new Regex(correctedBR, "Traffic available.*?:</TD><TD><b>([^<>\"\\']+)</b>").getMatch(0);
        if (availabletraffic != null && !availabletraffic.contains("nlimited") && !availabletraffic.equalsIgnoreCase(" Mb")) {
            ai.setTrafficLeft(SizeFormatter.getSize(availabletraffic));
        } else {
            ai.setUnlimitedTraffic();
        }
        if (account.getBooleanProperty("nopremium")) {
            ai.setStatus("Registered (free) User");
            try {
                account.setConcurrentUsePossible(false);
            } catch (Throwable e) {
            }
        } else {
            String expire = new Regex(correctedBR, Pattern.compile("<td>Premium(\\-| )Account expires?:</td>.*?<td>(<b>)?(\\d{1,2} [A-Za-z]+ \\d{4})(</b>)?</td>", Pattern.CASE_INSENSITIVE)).getMatch(2);
            if (expire == null) {
                ai.setExpired(true);
                account.setValid(false);
                return ai;
            } else {
                expire = expire.replaceAll("(<b>|</b>)", "");
                ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "dd MMMM yyyy", null));
            }
            try {
                account.setConcurrentUsePossible(true);
            } catch (Throwable e) {
            }
            ai.setStatus("Premium User");
        }
        return ai;
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        String passCode = null;
        requestFileInformation(link);
        login(account, false);
        br.setFollowRedirects(false);
        String dllink = null;
        if (account.getBooleanProperty("nopremium")) {
            getPage(link.getDownloadURL());
            doFree(link, true, -2, "freelink2");
        } else {
            dllink = checkDirectLink(link, "premlink");
            if (dllink == null) {
                getPage(link.getDownloadURL());
                dllink = getDllink();
                if (dllink == null) {
                    checkErrors(link, true, passCode);
                    Form dlform = br.getFormbyProperty("name", "F1");
                    if (dlform == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    if (new Regex(correctedBR, PASSWORDTEXT).matches()) {
                        passCode = handlePassword(passCode, dlform, link);
                    }
                    sendForm(dlform);
                    dllink = getDllink();
                    checkErrors(link, true, passCode);
                }
            }
            if (dllink == null) {
                logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            logger.info("Final downloadlink = " + dllink + " starting the download...");
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
            if (dl.getConnection().getContentType().contains("html")) {
                logger.warning("The final dllink seems not to be a file!");
                br.followConnection();
                correctBR();
                checkServerErrors();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (passCode != null) {
                link.setProperty("pass", passCode);
            }
            link.setProperty("premlink", dllink);
            dl.startDownload();
        }
    }

    @SuppressWarnings("unchecked")
    private void login(Account account, boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                /** Load cookies */
                br.setCookiesExclusive(true);
                br.setCookie(COOKIE_HOST, "lang", "english");
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?> && !force) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            this.br.setCookie(COOKIE_HOST, key, value);
                        }
                        return;
                    }
                }
                getPage(COOKIE_HOST);
                String loginpage = new Regex(correctedBR, "\"(https?://(www\\.)?filerio\\.in/[^<>\"]*?)\">Login").getMatch(0);
                if (loginpage == null) {
                    logger.info("Failed to find loginpage --> Using default");
                    loginpage = "/logmenow.html";
                } else {
                    logger.info("Found loginpage: " + loginpage);
                }
                getPage(loginpage);
                Form loginform = br.getForm(0);
                if (loginform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                loginform.put("login", Encoding.urlEncode(account.getUser()));
                loginform.put("password", Encoding.urlEncode(account.getPass()));
                loginform.put("redirect", "http://filerio.in/");
                loginform.remove(null);
                sendForm(loginform);
                if (br.getCookie(COOKIE_HOST, "login") == null || br.getCookie(COOKIE_HOST, "xfss") == null) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                String accpage = new Regex(correctedBR, "\"(https?://filerio`\\.in/\\?[^<>\"]*?)\">My Account</a>").getMatch(0);
                if (accpage == null) {
                    logger.info("Failed to find accountoverview_page --> Using default");
                    accpage = "/?op=view_account";
                } else {
                    logger.info("Found accountoverview_page --> Using: " + accpage);
                }
                getPage(accpage);
                if (!correctedBR.contains("\"Extend Premium Account\"")) {
                    account.setProperty("nopremium", true);
                } else {
                    account.setProperty("nopremium", false);
                }
                /** Save cookies */
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = this.br.getCookies(COOKIE_HOST);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return 1;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    private void waitTime(long timeBefore, DownloadLink downloadLink) throws PluginException {
        int passedTime = (int) ((System.currentTimeMillis() - timeBefore) / 1000) - 1;
        /** Ticket Time */
        final String ttt = new Regex(correctedBR, "<div class=\"wait\\-txt\"><span id=\"[a-z0-9]+\">(\\d+)</span></div>").getMatch(0);
        if (ttt != null) {
            int tt = Integer.parseInt(ttt);
            tt -= passedTime;
            logger.info("Waittime detected, waiting " + ttt + " - " + passedTime + " seconds from now on...");
            if (tt > 0) {
                sleep(tt * 1000l, downloadLink);
            }
        }
    }

    private static String fixLinkSSL(String link) {
        if (checkSsl()) {
            link = link.replace("http://", "https://");
        } else {
            link = link.replace("https://", "http://");
        }
        return link;
    }

    private static boolean checkSsl() {
        return SubConfiguration.getConfig("filerio.in").getBooleanProperty(SSL_CONNECTION, false) || SubConfiguration.getConfig("filerio.com").getBooleanProperty(SSL_CONNECTION, false) || SubConfiguration.getConfig("filekeen.com").getBooleanProperty(SSL_CONNECTION, false);
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), SSL_CONNECTION, JDL.L("plugins.hoster.FileRioCom.preferSSL", "Use Secure Communication over SSL (HTTPS://)")).setDefaultValue(false));
    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("nopremium"))) {
            /* free accounts also have captchas */
            return true;
        }
        return false;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.SibSoft_XFileShare;
    }

}