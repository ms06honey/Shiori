package com.example.shiori.core.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramMediaExtractorTest {

    @Test
    fun extract_prefersLargestInstagramImageOverSquareOgThumbnail() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Instagram post" />
                <meta property="og:image" content="https://cdn.example.com/og-square.jpg" />
                <script>
                  window._sharedData = {
                    "entry_data": {
                      "PostPage": [{
                        "graphql": {
                          "shortcode_media": {
                            "display_resources": [
                              {"src":"https://cdn.example.com/320x400.jpg","config_width":320,"config_height":400},
                              {"src":"https://cdn.example.com/640x800.jpg","config_width":640,"config_height":800},
                              {"src":"https://cdn.example.com/1080x1350.jpg","config_width":1080,"config_height":1350}
                            ],
                            "display_url":"https://cdn.example.com/1080x1350.jpg",
                            "thumbnail_src":"https://cdn.example.com/thumb-square.jpg",
                            "accessibility_caption":"portrait image"
                          }
                        }
                      }]
                    }
                  };
                </script>
              </head>
              <body></body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.instagram.com/p/ABC123/")

        val result = InstagramMediaExtractor.extract(doc, "https://www.instagram.com/p/ABC123/")

        assertEquals("https://cdn.example.com/1080x1350.jpg", result?.imageUrl)
        assertEquals(
            listOf(
                "https://cdn.example.com/1080x1350.jpg",
                "https://cdn.example.com/640x800.jpg",
                "https://cdn.example.com/320x400.jpg",
                "https://cdn.example.com/thumb-square.jpg"
            ),
            result?.allImageUrls
        )
        assertEquals("portrait image", result?.description)
    }

    @Test
    fun extract_collectsCarouselImagesInPostOrder() {
        val html = """
            <html>
              <head>
                <script>
                  window.__additionalDataLoaded('/p/CAROUSEL/', {
                    "graphql": {
                      "shortcode_media": {
                        "edge_sidecar_to_children": {
                          "edges": [
                            {
                              "node": {
                                "image_versions2": {
                                  "candidates": [
                                    {"url":"https://cdn.example.com/1-small.jpg","width":200,"height":250},
                                    {"url":"https://cdn.example.com/1-large.jpg","width":1080,"height":1350}
                                  ]
                                }
                              }
                            },
                            {
                              "node": {
                                "image_versions2": {
                                  "candidates": [
                                    {"url":"https://cdn.example.com/2-small.jpg","width":200,"height":200},
                                    {"url":"https://cdn.example.com/2-large.jpg","width":1440,"height":1440}
                                  ]
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                  });
                </script>
              </head>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.instagram.com/p/CAROUSEL/")

        val result = InstagramMediaExtractor.extract(doc, "https://www.instagram.com/p/CAROUSEL/")

        assertEquals(
            listOf(
                "https://cdn.example.com/1-large.jpg",
                "https://cdn.example.com/1-small.jpg",
                "https://cdn.example.com/2-large.jpg",
                "https://cdn.example.com/2-small.jpg"
            ),
            result?.allImageUrls
        )
    }

    @Test
    fun extract_readsXdtApiPayloadAndVideoUrl() {
        val html = """
            <html>
              <head>
                <script type="application/json">
                  {
                    "data": {
                      "xdt_api__v1__media__shortcode__web_info": {
                        "items": [
                          {
                            "image_versions2": {
                              "candidates": [
                                {"url":"https://cdn.example.com/video-cover-small.jpg","width":320,"height":320},
                                {"url":"https://cdn.example.com/video-cover-large.jpg","width":1080,"height":1080}
                              ]
                            },
                            "video_url": "https://cdn.example.com/video.mp4"
                          }
                        ]
                      }
                    }
                  }
                </script>
              </head>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.instagram.com/reel/XYZ987/")

        val result = InstagramMediaExtractor.extract(doc, "https://www.instagram.com/reel/XYZ987/")

        assertEquals("https://cdn.example.com/video-cover-large.jpg", result?.imageUrl)
        assertEquals("https://cdn.example.com/video.mp4", result?.videoUrl)
        assertTrue(result?.allVideoUrls?.contains("https://cdn.example.com/video.mp4") == true)
    }

    @Test
    fun extract_detectsVideoVersionsUrlWithoutFileExtension() {
        val html = """
            <html>
              <head>
                <script>
                  window.__additionalDataLoaded('/reel/NOEXT/', {
                    "graphql": {
                      "shortcode_media": {
                        "is_video": true,
                        "display_resources": [
                          {"src":"https://cdn.example.com/cover-small.jpg","config_width":320,"config_height":320},
                          {"src":"https://cdn.example.com/cover-large.jpg","config_width":1080,"config_height":1080}
                        ],
                        "video_versions": [
                          {
                            "type": 101,
                            "mime_type": "video/mp4",
                            "url": "https://instagram.fxyz1-1.fna.fbcdn.net/o1/v/t16/f2/m86/abcd1234?efg=1"
                          }
                        ]
                      }
                    }
                  });
                </script>
              </head>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.instagram.com/reel/NOEXT/")

        val result = InstagramMediaExtractor.extract(doc, "https://www.instagram.com/reel/NOEXT/")

        assertEquals("https://cdn.example.com/cover-large.jpg", result?.imageUrl)
        assertEquals(
            "https://instagram.fxyz1-1.fna.fbcdn.net/o1/v/t16/f2/m86/abcd1234?efg=1",
            result?.videoUrl
        )
        assertTrue(
            result?.allVideoUrls?.contains(
                "https://instagram.fxyz1-1.fna.fbcdn.net/o1/v/t16/f2/m86/abcd1234?efg=1"
            ) == true
        )
    }

    @Test
    fun extract_detectsPlaybackUrlInsideVideoContainer() {
        val html = """
            <html>
              <head>
                <script type="application/json">
                  {
                    "data": {
                      "xdt_api__v1__media__shortcode__web_info": {
                        "items": [
                          {
                            "clips_metadata": {
                              "playback_options": {
                                "playback_url": "https://instagram.cdn.example.com/reelPlayback?id=999"
                              }
                            },
                            "image_versions2": {
                              "candidates": [
                                {"url":"https://cdn.example.com/reel-cover.jpg","width":1080,"height":1350}
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                </script>
              </head>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.instagram.com/reel/PLAYBACK/")

        val result = InstagramMediaExtractor.extract(doc, "https://www.instagram.com/reel/PLAYBACK/")

        assertEquals("https://instagram.cdn.example.com/reelPlayback?id=999", result?.videoUrl)
        assertTrue(result?.allVideoUrls?.isNotEmpty() == true)
    }
}
