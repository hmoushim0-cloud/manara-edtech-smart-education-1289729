# منصة سقر لمراجعة القدرات والتحصيلي — تحليل وتصحيح أسئلة الاختبارات بتقنية القلم الذكي (`manara-edtech-smart-education-1289729`)
> مراجعة فورية بتقنية القلم الذكي وبلوتوث منخفض الطاقة — دقة 4096 مستوى

- **Project ID**: `#1289729`
- **Commercial Archetype**: `smart_education`
- **Bound Hardware Peripheral**: `Smart Stylus Digitizer 4096-Level + BLE Beacon`
- **Package Name**: `sa.factory.manaraedtechsmarte`
- **Zero-Emoji Invariant**: Strictly Enforced (100% Vector Icons)

---

## [Sidekick Autonomous Update] - Iteration 95

### Overview of Completed Enhancements
- **SaudiSmartStylusDigitizerEngine**:
  - Implements 4096-level active stylus digitizer telemetry for high-precision Arabic handwriting and mathematical notation.
  - Multi-curve pressure calibration: Linear, Calligraphic Smooth (Sigmoid S-curve), and Firm Exam Note (Quadratic curve).
  - High-performance palm rejection filter discriminating active pen tip vs capacitive wrist/palm touch contacts (>12mm threshold).
  - Exponential moving average (EMA) micro-jitter smoothing filter.
  - Binary telemetry frame serialization with CRC-16 CCITT validation and BLE exam hall desk beacon proximity ranging (Path Loss Formula).

- **SaudiQiyasExamGradingEngine**:
  - Standardized scoring for Saudi National Center for Assessment (Qiyas) examinations: GAT (القدرات العامة - كمي ولفظي) and Tahsili (التحصيلي - رياضيات، فيزياء، كيمياء، أحياء).
  - Standard equating engine: Normalized T-Score scale (Mean = 65.0, SD = 10.0) and percentile ranking conversion.
  - Smart exam telemetry anomaly detection: Rapid-guessing detector (time < 6s with faint stylus pressure) and section fatigue auditor.
  - Saudi Student 10-digit National ID / Iqama format validation (Luhn modulus 10 check).

- **Build Infrastructure & Clean UI Modernization**:
  - Integrated official Gradle 8.10.2 wrapper (`gradlew` and `gradle-wrapper.jar`).
  - Relocated regression tests from `main/java/test` into standard `app/src/test/java/sa/factory/manaraedtechsmarte/`.
  - Resolved Compose imports for `FontWeight` and `TextUnit.sp` in `MainActivity.kt`.
  - Fixed single-quote character literal bugs in `HomeScreen.kt` and `TelemetryScreen.kt`.
  - Replaced deprecated Material3 `Divider` with `HorizontalDivider`.
  - Purged all unicode emojis across codebase and UI screens.
  - Implemented 10 comprehensive unit tests with 100% green assertions.

---

### Step-by-Step Build and Test Instructions

1. **Clone the repository**:
   ```bash
   git clone https://github.com/hmoushim0-cloud/manara-edtech-smart-education-1289729.git
   cd manara-edtech-smart-education-1289729
   ```

2. **Run Unit Tests**:
   ```bash
   ./gradlew test
   ```

3. **Assemble Debug APK**:
   ```bash
   ./gradlew :app:assembleDebug
   ```

4. **Run on Cloud Test Harness**:
   - Triggers automatically via GitHub Actions workflow on `hmoushim0-cloud/Android-test-harness`.
   - Boots Pixel 6 emulator (API 34) and executes full diagnostic activity flow.
