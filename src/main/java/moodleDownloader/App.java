package moodleDownloader;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * moodleDownloader is a program that downloads all files from the courses on moodle.htw-berlin.de
 *
 * @author Julian Krieft
 */
public class App {
    /**
     * username for moodle login
     */
    private static String name;

    /**
     * password for moodle login
     */
    private static String password;

    /**
     * root directory for downloaded files
     */
    private static String outputDir;

    /**
     * session cookies
     */
    private static Map<String, String> loginCookies;

    /**
     * list of files where download failed
     */
    private static LinkedList<String> notDownloadedFiles = new LinkedList<String>();

    /**
     * login to moodle and return my courses page
     *
     * @param user username
     * @param pw   password
     * @return my courses page
     */
    private static Document loginAndGetMyCoursesHTML(String user, String pw) {
        System.out.println("Anmelden...");

        //login to moodle
        try {
            Connection.Response res = Jsoup
                    .connect("https://moodle.htw-berlin.de/login/index.php")
                    .userAgent("Mozilla")
                    .timeout(10000) //timeout 10 sec
                    .data("username", user, "password", pw)
                    .method(Connection.Method.POST)
                    .execute();

            loginCookies = res.cookies();
            Document doc = res.parse();

            //check if login was successfull, by checking if my courses page did load
            if (!doc.body().toString().contains("Meine Kurse")) {
                System.out.println("Login fehlgeschlagen.");
                System.exit(1);
            }

            return doc;
        } catch (Exception e) {
            System.out.println("\nEs konnte keine Verbundung zu moodle.htw-berlin.de aufgebaut werden.\n" +
                    "Bitte überprüfe deine Internetverbindung und versuche es später erneut.");
            System.exit(2);
            return null;
        }
    }

    /**
     * extract course names and URLs from web document
     *
     * @param doc document of my courses page
     * @return map containing courses and URLs
     */
    private static LinkedHashMap<String, String> exctractCourses(Document doc) {
        LinkedHashMap<String, String> courses = new LinkedHashMap<String, String>();
        for (Element elem : doc.select("div.coc-course").select("a")) {
            //extract all links to course pages
            if (elem.attr("href").contains("/course/")) {
                //if(elem.attr("href").equals("https://moodle.htw-berlin.de/course/view.php?id=10917"))
                courses.put(elem.attr("title"), elem.attr("href"));
            }
        }
        return courses;
    }

    /**
     * prompt for login
     */
    private static void readLogin() {
        Scanner inputScanner = new Scanner(System.in);
        System.out.println("Downloader für moodle.htw-berlin.de");
        System.out.println("-----------------------------------\n");
        System.out.println("Bitte Login-Daten eingeben");

        System.out.print("Loginname: ");
        name = inputScanner.nextLine();


        if(System.console() != null) {
            //read password with hidden input (won't work in most IDE consoles)
            Console console = System.console();
            char[] passwordArray = console.readPassword("Passwort: ");
            password = new String(passwordArray);
        }
        else {
            //read password fallback for useage in IDE console (password not hidden!!)
            System.out.print("Passwort (eingeblendet): ");
            password = inputScanner.nextLine();
        }

        System.out.print("Download-Verzeichnis: ");
        outputDir = inputScanner.nextLine();
    }

    /**
     * check if output directory exists. if not it will be created
     */
    private static void checkDirectroy() {
        if (!Files.isDirectory(Paths.get(outputDir))) {
            File dir = new File(outputDir);
            dir.mkdir();
        }
    }

    /**
     * extract files from course page
     *
     * @param courseURL URl of course page
     * @return map with files and their URLs
     */
    private static LinkedHashMap<String, String> extractCourseFiles(String courseURL) {
        List<String> pageURLs = new ArrayList<String>();

        //load course page with loginCookies
        try {
            Document doc = Jsoup.connect(courseURL)
                    .cookies(loginCookies)
                    .get();

            for (Element elem : doc.select("div.activityinstance").select("a")) {
                pageURLs.add(elem.attr("href"));
            }

        } catch (IOException e) {
            System.out.println("\nDie Verbindung wurde unterbrochen.\n" +
                    "Bitte überprüfe deine Internetverbindung und versuche es später erneut.");
            System.exit(3);
        }

        LinkedHashMap<String, String> files = new LinkedHashMap<String, String>();

        for (String pageURL : pageURLs) {
            //load file page
            Document pageDoc;
            try {
                pageDoc = Jsoup.connect(pageURL)
                        .ignoreContentType(true)
                        .cookies(loginCookies)
                        .get();
            } catch (Exception e) {
                System.out.println("FEHLER! Datei (" + pageURL + ") konnte nicht gespeichert werden und wird übersprungen.");
                notDownloadedFiles.add(pageURL);
                continue;
            }

            //page for a normal downloadable file
            if (pageDoc.body().toString().contains("Klicken Sie auf den Link '")) {
                for (Element elem : pageDoc.select("div.resourceworkaround").select("a")) {
                    String fileURL = elem.attr("href");
                    String fileName = elem.text();
                    files.put(fileName, fileURL);
                    System.out.println("Datei gefunden: " + fileName);
                }
            }

            //page for a folder
            else if (pageURL.contains("/folder/")) {
                //extract all files inside folder
                for (Element elem : pageDoc.select("span.fp-filename-icon").select("a")) {
                    String fileURL = elem.attr("href");
                    String fileName = elem.text();

                    files.put(fileName, fileURL.replace("?forcedownload=1", ""));
                    System.out.println("Datei gefunden: " + fileName);
                }
            }

            //page for external URL
            else if (pageURL.contains("/url/")) {
                for (Element elem : pageDoc.select("div.urlworkaround").select("a")) {
                    String linkURL = elem.attr("href");
                    //<h2>Course: Scientific Python</h2>

                    String linkName = elem.text();

                    System.out.println("Externer Link gefunden: " + linkURL);

                    //hack for external links
                    files.put("extURL:" + linkName, linkURL);
                }
            }

            //moodle page
            else if (pageURL.contains("/page/")) {
                System.out.println("Moodle-Seite gefunden: " + pageURL);
                files.put(pageDoc.title().replace(" ", "-").replace(":", "").replace(".", "") + ".html", pageURL);
            }
        }

        return files;
    }

    /**
     * downloads a file
     *
     * @param courseDir output directory
     * @param fileName  name of the file
     * @param fileURL   URL of the file in moodle
     * @throws IOException if file could not be written or downloaded
     */
    private static void downloadFile(String courseDir, String fileName, String fileURL) throws IOException {
        URL url = new URL(fileURL);
        URLConnection con = url.openConnection();

        //build cookie string for download with session
        String cookieString = "";
        for (Map.Entry<String, String> cookie : loginCookies.entrySet()) {
            cookieString += cookie.getKey() + "=" + cookie.getValue() + "; ";
        }
        con.setRequestProperty("Cookie", cookieString);

        //download file
        InputStream in = con.getInputStream();
        File fileOut = new File(outputDir + "/" + courseDir + "/" + fileName);
        Files.copy(in, fileOut.toPath());
    }

    public static void main(String[] args) {
        readLogin();
        Document doc = loginAndGetMyCoursesHTML(name, password);
        checkDirectroy();

        //get all courses and iterate over them
        LinkedHashMap<String, String> courses = exctractCourses(doc);
        for (Map.Entry<String, String> course : courses.entrySet()) {

            //print underlined course name
            System.out.println("\n\nKurs: " + course.getKey());
            System.out.print("------");
            for (char ignored : course.getKey().toCharArray()) {
                System.out.print("-");
            }
            System.out.println();


            //extract files for course
            LinkedHashMap<String, String> courseFiles = extractCourseFiles(course.getValue());

            //if course has downloadable files
            if (!courseFiles.isEmpty()) {
                //create directory for course
                String courseDir = course.getKey().replaceAll(" ", "-");
                File dir = new File(outputDir + "/" + courseDir);
                dir.mkdir();

                String externalLinks = "";

                //download all files and save external links
                for (Map.Entry<String, String> file : courseFiles.entrySet()) {
                    if (!file.getKey().startsWith("extURL:")) {
                        System.out.println("Datei wird heruntergeladen: " + file.getKey());
                        try {
                            downloadFile(courseDir, file.getKey(), file.getValue());
                        } catch (Exception e) {
                            System.out.println("FEHLER! Datei (" + file.getValue() + ") konnte nicht gespeichert werden und wird übersprungen.");
                            notDownloadedFiles.add(file.getValue());
                        }
                    } else {
                        externalLinks += "<a href=\"" + file.getValue() + "\">" + file.getValue() + "</a><br />\n";
                    }
                }

                //write external links to html file
                if (!externalLinks.equals("")) {
                    System.out.println("Datei mit externen Links wird gespeichert");
                    Path target = Paths.get(outputDir + "/" + courseDir + "/" + "Externe Links.html");
                    InputStream is = new ByteArrayInputStream(externalLinks.getBytes());
                    try {
                        Files.copy(is, target);
                    } catch (Exception e) {
                        System.out.println("Datei mit externen Links konnte nicht gespeichert werden!");
                    }
                }
            } else {
                //empty course
                System.out.println("Dieser Kurs ist leer");
            }
        }
        System.out.println("\n\nDownloads abgeschlossen.");


        //print all files where download did fail
        if (!notDownloadedFiles.isEmpty()) {
            System.out.println("Die folgenden Dateien konnten nicht heruntergeladen werden. Bitte lade die Dateien manuell herunter!");
            for (String elem : notDownloadedFiles) {
                System.out.println("-> " + elem);
            }
        }
    }
}