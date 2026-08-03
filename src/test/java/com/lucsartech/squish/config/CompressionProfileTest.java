package com.lucsartech.squish.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CompressionProfile.
 */
@DisplayName("CompressionProfile")
class CompressionProfileTest {

    @Nested
    @DisplayName("Mode-based profiles")
    class ModeBasedProfiles {

        @Test
        @DisplayName("should create profile with LOSSLESS mode")
        void losslessMode() {
            var profile = new CompressionProfile("test", CompressionMode.LOSSLESS);

            assertThat(profile.getName()).isEqualTo("test");
            assertThat(profile.getMode()).isEqualTo(CompressionMode.LOSSLESS);
            assertThat(profile.isLossless()).isTrue();
            assertThat(profile.getEffectiveScaleFactor()).isEqualTo(1.0f);
            assertThat(profile.getEffectiveJpegQuality()).isEqualTo(1.0f);
            assertThat(profile.isCustom()).isFalse();
        }

        @Test
        @DisplayName("should create profile with MEDIUM mode")
        void mediumMode() {
            var profile = new CompressionProfile("test", CompressionMode.MEDIUM);

            assertThat(profile.getMode()).isEqualTo(CompressionMode.MEDIUM);
            assertThat(profile.isLossless()).isFalse();
            assertThat(profile.getEffectiveScaleFactor()).isEqualTo(0.75f);
            assertThat(profile.getEffectiveJpegQuality()).isEqualTo(0.7f);
        }

        @Test
        @DisplayName("should create profile with AGGRESSIVE mode")
        void aggressiveMode() {
            var profile = new CompressionProfile("test", CompressionMode.AGGRESSIVE);

            assertThat(profile.getMode()).isEqualTo(CompressionMode.AGGRESSIVE);
            assertThat(profile.isLossless()).isFalse();
            assertThat(profile.getEffectiveScaleFactor()).isEqualTo(0.5f);
            assertThat(profile.getEffectiveJpegQuality()).isEqualTo(0.3f);
        }
    }

    @Nested
    @DisplayName("Custom profiles")
    class CustomProfiles {

        @Test
        @DisplayName("should create profile with custom scale and quality")
        void customValues() {
            var profile = new CompressionProfile("custom", 0.5f, 0.4f);

            assertThat(profile.getName()).isEqualTo("custom");
            assertThat(profile.isCustom()).isTrue();
            assertThat(profile.getEffectiveScaleFactor()).isEqualTo(0.5f);
            assertThat(profile.getEffectiveJpegQuality()).isEqualTo(0.4f);
            assertThat(profile.isLossless()).isFalse();
        }

        @Test
        @DisplayName("should be lossless when scale and quality are 1.0")
        void customLossless() {
            var profile = new CompressionProfile("lossless-custom", 1.0f, 1.0f);

            assertThat(profile.isLossless()).isTrue();
            assertThat(profile.isCustom()).isTrue();
        }

        @Test
        @DisplayName("custom values should override mode settings")
        void customOverridesMode() {
            var profile = new CompressionProfile();
            profile.setName("mixed");
            profile.setMode(CompressionMode.AGGRESSIVE);
            profile.setCustomScaleFactor(0.8f);

            assertThat(profile.getEffectiveScaleFactor()).isEqualTo(0.8f);
            // Quality should still come from mode since customJpegQuality is null
            assertThat(profile.getEffectiveJpegQuality()).isEqualTo(0.3f);
            assertThat(profile.isCustom()).isTrue();
        }
    }

    @Nested
    @DisplayName("Predefined profiles")
    class PredefinedProfiles {

        @Test
        @DisplayName("archival profile should be lossless with PDF/A")
        void archivalProfile() {
            var profile = CompressionProfile.archival();

            assertThat(profile.getName()).isEqualTo("archival");
            assertThat(profile.isLossless()).isTrue();
            assertThat(profile.isPdfaEnabled()).isTrue();
            assertThat(profile.getPdfaConformance())
                    .isEqualTo(CompressionProfile.PdfAConformance.PDF_A_2B);
        }

        @Test
        @DisplayName("office profile should use MEDIUM mode")
        void officeProfile() {
            var profile = CompressionProfile.office();

            assertThat(profile.getName()).isEqualTo("office");
            assertThat(profile.getMode()).isEqualTo(CompressionMode.MEDIUM);
            assertThat(profile.isLossless()).isFalse();
        }

        @Test
        @DisplayName("maximum profile should use AGGRESSIVE mode")
        void maximumProfile() {
            var profile = CompressionProfile.maximum();

            assertThat(profile.getName()).isEqualTo("maximum");
            assertThat(profile.getMode()).isEqualTo(CompressionMode.AGGRESSIVE);
        }

        @Test
        @DisplayName("confidential profile should have watermark enabled")
        void confidentialProfile() {
            var profile = CompressionProfile.confidential();

            assertThat(profile.getName()).isEqualTo("confidential");
            assertThat(profile.isWatermarkEnabled()).isTrue();
            assertThat(profile.getWatermarkText()).isEqualTo("CONFIDENTIAL");
            assertThat(profile.getWatermarkPosition())
                    .isEqualTo(CompressionProfile.WatermarkPosition.DIAGONAL);
        }
    }

    @Nested
    @DisplayName("Watermark settings")
    class WatermarkSettings {

        @Test
        @DisplayName("should have default watermark settings")
        void defaultWatermarkSettings() {
            var profile = new CompressionProfile("test", CompressionMode.MEDIUM);

            assertThat(profile.isWatermarkEnabled()).isFalse();
            assertThat(profile.getWatermarkPosition())
                    .isEqualTo(CompressionProfile.WatermarkPosition.CENTER);
            assertThat(profile.getWatermarkOpacity()).isEqualTo(0.3f);
            assertThat(profile.getWatermarkFontName()).isEqualTo("Helvetica");
            assertThat(profile.getWatermarkFontSize()).isEqualTo(48);
            assertThat(profile.getWatermarkColor()).isEqualTo("#888888");
        }

        @Test
        @DisplayName("should allow setting watermark properties")
        void customWatermarkSettings() {
            var profile = new CompressionProfile("test", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("DRAFT");
            profile.setWatermarkPosition(CompressionProfile.WatermarkPosition.BOTTOM_RIGHT);
            profile.setWatermarkOpacity(0.5f);

            assertThat(profile.isWatermarkEnabled()).isTrue();
            assertThat(profile.getWatermarkText()).isEqualTo("DRAFT");
            assertThat(profile.getWatermarkPosition())
                    .isEqualTo(CompressionProfile.WatermarkPosition.BOTTOM_RIGHT);
            assertThat(profile.getWatermarkOpacity()).isEqualTo(0.5f);
        }
    }

    @Nested
    @DisplayName("PDF/A settings")
    class PdfASettings {

        @Test
        @DisplayName("should have PDF/A disabled by default")
        void pdfaDisabledByDefault() {
            var profile = new CompressionProfile("test", CompressionMode.MEDIUM);

            assertThat(profile.isPdfaEnabled()).isFalse();
            assertThat(profile.getPdfaConformance())
                    .isEqualTo(CompressionProfile.PdfAConformance.PDF_A_2B);
        }

        @Test
        @DisplayName("should allow enabling PDF/A with conformance level")
        void enablePdfA() {
            var profile = new CompressionProfile("test", CompressionMode.LOSSLESS);
            profile.setPdfaEnabled(true);
            profile.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_3B);

            assertThat(profile.isPdfaEnabled()).isTrue();
            assertThat(profile.getPdfaConformance())
                    .isEqualTo(CompressionProfile.PdfAConformance.PDF_A_3B);
        }
    }

    @Test
    @DisplayName("toString should include relevant information")
    void toStringTest() {
        var profile = CompressionProfile.confidential();
        String str = profile.toString();

        assertThat(str).contains("confidential");
        assertThat(str).contains("MEDIUM");
        assertThat(str).contains("watermark='CONFIDENTIAL'");
    }
}
