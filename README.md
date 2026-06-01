# Palm-HighRes-Capture

### Description

This application is designed for rapid field-capture of georeferenced coconut palm imagery. It uses a YOLOv8 real-time object detection trigger to automatically save high-quality (10MB+), geotagged JPEG images to the Android device.

**Credits:**
99.9% of this codebase was developed by **Aubrey Moore** as the [CRB-damage-app](https://github.com/aubreymoore/CRB-damage-app). This fork, **Palm-HighRes-Capture**, builds upon his work with AI image classification in YOLO to transition the tool from mobile-only classification to a professional data-collection tool.

### Purpose
The goal is to generate high-resolution datasets in the field that are automatically georeferenced with GPS EXIF metadata. These images are intended for downstream processing using the **SAM3 (Segment Anything Model 3)** architecture to perform high-precision detection and health classification of Coconut Rhinoceros Beetle (CRB) damage.

### Key Features

*   **YOLOv8 Automatic Trigger:** Automatically identifies palms in the camera feed to trigger a capture.
*   **High-Res Output:** Captures full camera sensor resolution (approx. 10MB per file) for precise lab analysis.
*   **Georeferenced:** Embeds GPS coordinates directly into the photo's EXIF metadata.
*   **Double Logging:** Saves coordinates to both a daily CSV log and the image files themselves.
*   **Throttle Control:** Includes a 3-second delay between captures to prevent duplicate logging of the same tree during roadside surveys.

### Installation

Clone this repository and open the resulting folder with Android Studio. Then compile the app and deploy it to an attached Android device.


### License

The original work integrated by Aubrey Moore is from [surendramaran/YOLO](https://github.com/surendramaran/YOLO) and is licensed under the [MIT License](https://github.com/surendramaran/YOLO/blob/main/YOLOv8-Object-Detector-Android-Tflite/LICENSE).