# FW Driver

The FordWalls driver app for receiving transport jobs, completing daily pre-starts, recording pickup and delivery checks, and collecting signatures and photos.

## Download

[![Click here to download FW Driver](https://img.shields.io/badge/CLICK_HERE_TO_DOWNLOAD-FW_DRIVER_v1.4.4-168A5B?style=for-the-badge&logo=android&logoColor=white)](https://github.com/sargteapot/transport/raw/refs/heads/main/release/FW_Driver_v1.4.4.apk)

Android may ask for permission to install an app from your browser or file manager. Allow it when prompted, then open the downloaded APK.

## Dispatch portal

[![Open FW Dispatch Portal](https://img.shields.io/badge/OPEN_FW_DISPATCH_PORTAL-portal.fordwalls.co.nz-1565C0?style=for-the-badge&logo=googlechrome&logoColor=white)](https://portal.fordwalls.co.nz)

## Current release

**FW Driver v1.4.4**

- Multi-company selection and isolated company data
- Mandatory daily vehicle pre-start
- Automatic logout after 15 hours
- Driver job dispatch, quick acceptance, pickup and delivery checks
- Signatures, photos, and completed-job management
- Improved dark-mode contrast and mobile button layouts
- Rounded, consistent dashboard, camera, Settings and workflow controls
- Photo-only job evidence camera with a simpler Done flow
- Pickup and delivery checklist drafts are retained when adding photos
- Delivery vehicle verification now matches the pickup scan workflow

### Photo and signature cloud evidence

- Job-linked photos are saved on the phone first, then uploaded to
  Firebase Storage under the authenticated driver, selected company and job.
- Driver login is verified by the `driverLogin` callable function and exchanged
  for a Firebase Authentication custom token. The app no longer queries
  Firestore for a readable username/password pair.
- Every upload has a Firestore evidence record showing uploading, ready or
  failed state. Failed media stays on the phone and can be retried from the
  vehicle gallery.
- Driver and customer signatures upload before the job advances. A failed
  signature upload leaves the job at its previous status and shows the driver a
  retry message.
- Storage download tokens are not copied into Firestore. Only the protected
  Storage path is recorded, and FW Dispatch resolves it for an authorised user.
- FW Dispatch Build 17 reads the same job evidence records and displays the
  original photos, legacy videos and signatures.

### In-app updates

FW Driver checks an HTTPS JSON manifest when the app starts and from Settings.
The manifest location defaults to:

`https://transportercam-2ec51107.web.app/downloads/fw-driver-update.json`

Override it for a build with the Gradle property `FW_UPDATE_MANIFEST_URL`. For
example, add this to a user-level `gradle.properties` file (do not commit local
test URLs):

`FW_UPDATE_MANIFEST_URL=https://example.web.app/downloads/fw-driver-update.json`

The manifest format is:

```json
{
  "versionCode": 8,
  "versionName": "1.4.4",
  "minimumVersionCode": 6,
  "apkUrl": "https://example.web.app/downloads/fw-driver-1.4.4.apk",
  "sha256": "64-character lowercase SHA-256 value",
  "releaseNotes": ["First change", "Second change"]
}
```

The downloaded APK must use the same application ID and signing certificate as
the installed app. Its version and SHA-256 value must match the manifest before
FW Driver opens Android's installer. Android always retains control of the
install confirmation.
