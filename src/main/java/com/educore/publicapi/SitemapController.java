package com.educore.publicapi;

import com.educore.common.web.ApiProblemException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The sitemap of the public site (sitemaps.org protocol 0.9), anonymous, built from
 * {@code educore.seo.base-url}, the static public routes and every published course ({@code /courses/<slug>}).
 * <ul>
 *   <li>Sitemap file {@code n} ({@code GET /sitemap-courses-<n>.xml}) lists
 *       {@link PublicCatalogService#coursesPerSitemap()} published courses by name; file 1 also lists the
 *       static routes, so no file exceeds {@code educore.seo.sitemap-max-urls} URLs.</li>
 *   <li>{@code GET /sitemap.xml} is file 1 itself while one file holds everything, otherwise a sitemap index
 *       pointing to every file. No URL is ever dropped.</li>
 * </ul>
 * {@code lastmod} is the course's {@code updatedAt}; {@code /}, {@code /courses} and the index entries carry
 * the catalog revision (moves forward on unpublish and delete too); the content pages ({@code /about},
 * {@code /faq}, {@code /privacy}, {@code /security}) have no stored modification date, so they carry no
 * {@code lastmod}. Documents are written with a streaming XML writer, so every value is escaped. Unlike
 * {@code /api/**} these responses have no {@code X-Robots-Tag}.
 */
@RestController
@Validated
public class SitemapController {

    static final List<String> STATIC_ROUTES = List.of("/", "/courses", "/about", "/faq", "/privacy", "/security");

    /** Static routes whose content is the catalog itself, so the catalog revision is their lastmod. */
    private static final List<String> CATALOG_ROUTES = List.of("/", "/courses");

    private static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String FILE_PREFIX = "/sitemap-courses-";
    private static final String FILE_SUFFIX = ".xml";

    private final PublicCatalogService catalog;
    private final PublicCaching caching;

    public SitemapController(PublicCatalogService catalog, PublicCaching caching) {
        this.catalog = catalog;
        this.caching = caching;
    }

    @GetMapping(value = "/sitemap.xml", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> sitemap() {
        int files = catalog.sitemapFiles();
        return files == 1 ? file(1, files) : index(files);
    }

    /** Sitemap file {@code n} (1-based); 404 {@code sitemap/not-found} outside 1..number of files. */
    @GetMapping(value = FILE_PREFIX + "{file}" + FILE_SUFFIX,
            produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> sitemapFile(@PathVariable String file) {
        int files = catalog.sitemapFiles();
        int number = parseFileNumber(file);
        if (number < 1 || number > files) {
            throw ApiProblemException.notFound("sitemap/not-found", "No sitemap file exists at this address.");
        }
        return file(number, files);
    }

    private ResponseEntity<String> index(int files) {
        String base = catalog.baseUrl();
        Instant modified = catalog.catalogModified().orElse(null);
        return write(xml -> {
            xml.writeStartElement("sitemapindex");
            xml.writeDefaultNamespace(NAMESPACE);
            for (int n = 1; n <= files; n++) {
                xml.writeStartElement("sitemap");
                element(xml, "loc", base + FILE_PREFIX + n + FILE_SUFFIX);
                if (modified != null) {
                    element(xml, "lastmod", w3c(modified));
                }
                xml.writeEndElement();
            }
            xml.writeEndElement();
        });
    }

    private ResponseEntity<String> file(int number, int files) {
        String base = catalog.baseUrl();
        Instant catalogModified = catalog.catalogModified().orElse(null);
        List<PublicCourseResponse> courses = catalog.sitemapCourses(number);
        return write(xml -> {
            xml.writeStartElement("urlset");
            xml.writeDefaultNamespace(NAMESPACE);
            if (number == 1) {
                for (String route : STATIC_ROUTES) {
                    url(xml, base + route, CATALOG_ROUTES.contains(route) ? catalogModified : null);
                }
            }
            for (PublicCourseResponse course : courses) {
                url(xml, base + "/courses/" + course.slug(), course.updatedAt());
            }
            xml.writeEndElement();
        });
    }

    /** Only plain decimal numbers without sign or leading zeros address a file. */
    private static int parseFileNumber(String file) {
        if (file.isEmpty() || file.length() > 6 || file.charAt(0) == '0' || !file.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Integer.parseInt(file);
    }

    private ResponseEntity<String> write(XmlBody body) {
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            body.write(xml);
            xml.writeEndDocument();
            xml.close();
            return caching.document(out.toString(), MediaType.APPLICATION_XML);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Sitemap could not be written", e);
        }
    }

    private static void url(XMLStreamWriter xml, String location, Instant lastModified) throws XMLStreamException {
        xml.writeStartElement("url");
        element(xml, "loc", location);
        if (lastModified != null) {
            element(xml, "lastmod", w3c(lastModified));
        }
        xml.writeEndElement();
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(text);
        xml.writeEndElement();
    }

    private static String w3c(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
    }

    @FunctionalInterface
    private interface XmlBody {
        void write(XMLStreamWriter xml) throws XMLStreamException;
    }
}
