package com.educore.publicapi;

import com.educore.entity.Course;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * With more URLs than {@code educore.seo.sitemap-max-urls} (lowered to 10 here), {@code /sitemap.xml} is a
 * sitemap index and every published course is listed in exactly one {@code /sitemap-courses-<n>.xml} file;
 * no file exceeds the limit and no URL is dropped.
 */
@TestPropertySource(properties = "educore.seo.sitemap-max-urls=10")
class SitemapIndexIT extends PublicApiTestSupport {

    private static final String NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String BASE_URL = "http://localhost:3000";
    private static final int MAX_URLS = 10;

    private static final String INDEX_XSD = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="http://www.sitemaps.org/schemas/sitemap/0.9"
                       xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" elementFormDefault="qualified">
              <xs:element name="sitemapindex">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="sitemap" maxOccurs="50000">
                      <xs:complexType>
                        <xs:sequence>
                          <xs:element name="loc" type="xs:anyURI"/>
                          <xs:element name="lastmod" minOccurs="0">
                            <xs:simpleType>
                              <xs:restriction base="xs:string">
                                <xs:pattern value="\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+\\-]\\d{2}:\\d{2})"/>
                              </xs:restriction>
                            </xs:simpleType>
                          </xs:element>
                        </xs:sequence>
                      </xs:complexType>
                    </xs:element>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """;

    @Test
    void manyPublishedCoursesAreSplitAcrossFilesBehindAnIndex() throws Exception {
        List<String> slugs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Course course = publishedCourse(uniqueSlug("idx" + i), "Index " + i + ".");
            slugs.add(course.getSlug());
        }
        int published = jdbc.queryForObject("SELECT count(*) FROM course WHERE published", Integer.class);
        int perFile = MAX_URLS - SitemapController.STATIC_ROUTES.size();
        int files = (published + perFile - 1) / perFile;
        assertThat(files).isGreaterThan(1);

        MvcResult indexResult = perform(null, get("/sitemap.xml"), null);
        assertThat(indexResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(indexResult.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=300, public");
        assertThat(indexResult.getResponse().getHeader(HttpHeaders.ETAG)).matches("\"[0-9a-f]{32}\"");
        String index = indexResult.getResponse().getContentAsString();
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new StreamSource(new StringReader(INDEX_XSD)))
                .newValidator().validate(new StreamSource(new StringReader(index)));

        List<String> fileLocations = texts(parse(index), "loc");
        List<String> expected = new ArrayList<>();
        for (int n = 1; n <= files; n++) {
            expected.add(BASE_URL + "/sitemap-courses-" + n + ".xml");
        }
        assertThat(fileLocations).containsExactlyElementsOf(expected);

        Set<String> allLocations = new HashSet<>();
        int total = 0;
        for (int n = 1; n <= files; n++) {
            MvcResult file = perform(null, get("/sitemap-courses-" + n + ".xml"), null);
            assertThat(file.getResponse().getStatus()).isEqualTo(200);
            assertThat(file.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=300, public");
            assertThat(file.getResponse().getHeader("X-Robots-Tag")).isNull();
            Document document = parse(file.getResponse().getContentAsString());
            assertThat(document.getDocumentElement().getLocalName()).isEqualTo("urlset");
            List<String> locations = texts(document, "loc");
            assertThat(locations).hasSizeLessThanOrEqualTo(MAX_URLS);
            assertThat(locations.contains(BASE_URL + "/about")).isEqualTo(n == 1);
            total += locations.size();
            allLocations.addAll(locations);
        }
        assertThat(total).isEqualTo(allLocations.size()).isEqualTo(SitemapController.STATIC_ROUTES.size() + published);
        for (String slug : slugs) {
            assertThat(allLocations).contains(BASE_URL + "/courses/" + slug);
        }

        for (String outside : List.of("0", String.valueOf(files + 1), "01", "-1", "x", "1x")) {
            MvcResult missing = perform(null, get("/sitemap-courses-" + outside + ".xml"), null);
            assertThat(missing.getResponse().getStatus()).as(outside).isEqualTo(404);
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static List<String> texts(Document document, String element) {
        NodeList nodes = document.getElementsByTagNameNS(NS, element);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(((Element) nodes.item(i)).getTextContent());
        }
        return values;
    }
}
