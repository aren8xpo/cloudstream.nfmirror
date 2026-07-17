# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this Cloudstream3 plugin repository.

## Project Overview

This is a template repository for creating Cloudstream3 plugins. Cloudstream3 is an Android application for streaming movies and TV shows from various sources. Each plugin in this repository represents a different content provider.

The repository contains:
- A sample plugin (`NetflixMirrorProvider`) demonstrating how to create a Cloudstream3 plugin
- Gradle build configuration for building and deploying plugins
- Template files for creating new plugins

## Project Structure

```
TestPlugins/
├── NetflixProvider/          # Sample plugin implementation
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/       # Kotlin source code
        │   │   │   └── com/aloz/netflixmirror/NetflixProvider.kt
        │   │   ├── res/      # Resources (icons, layouts, strings)
        │   │   └── AndroidManifest.xml
        │   └── test/         # Test files
        ├── build.gradle.kts  # Plugin-specific Gradle configuration
        └── ...
├── build.gradle.kts          # Root build configuration
├── settings.gradle.kts       # Includes all subprojects (plugins)
├── gradle.properties         # Gradle configuration
├── gradlew / gradlew.bat     # Gradle wrapper scripts
└── README.md                 # General repository information
```

## Development Commands

### Building Plugins

To build a specific plugin:
```bash
# Using Gradle wrapper (Windows)
.\gradlew.bat <pluginName>:make

# Using Gradle wrapper (Linux/Mac)
./gradlew <pluginName>:make
```

Example:
```bash
.\gradlew.bat NetflixMirrorProvider:make
```

### Deploying Plugins

To deploy a plugin to a device via ADB:
```bash
# Using Gradle wrapper (Windows)
.\gradlew.bat <pluginName>:deployWithAdb

# Using Gradle wrapper (Linux/Mac)
./gradlew <pluginName>:deployWithAdb
```

Example:
```bash
.\gradlew.bat NetflixMirrorProvider:deployWithAdb
```

### Cleaning Build Output

```bash
# Clean a specific plugin
.\gradlew.bat <pluginName>:clean

# Clean all projects
.\gradlew.bat clean
```

### Common Development Tasks

1. **Creating a new plugin**: Copy an existing plugin directory and rename it, then update:
   - The package name in the Kotlin source file
   - The plugin name in the CloudstreamPlugin annotation
   - The `name`, `mainUrl`, and other properties in the plugin class
   - Update `build.gradle.kts` if needed (though most configuration is inherited)

2. **Updating plugin metadata**: Edit the properties in the plugin class annotated with `@CloudstreamPlugin`:
   - `name`: Display name of the plugin
   - `mainUrl`: Base URL of the content source
   - `supportedTypes`: What types of content the plugin provides (Movie, TvSeries, etc.)
   - `hasMainPage`: Whether the plugin should appear on the home screen
   - Other metadata like `language`, `requiresResources`, `tvTypes`, etc.

3. **Implementing core functionality**: A Cloudstream3 plugin must implement:
   - `getMainPage()`: Returns content for the plugin's main page
   - `search()`: Handles search queries
   - `load()`: Loads detailed information about a specific item
   - `loadLinks()`: Provides playable links for content

## Important Files

### NetflixMirrorProvider/src/main/kotlin/com/aloz/netflixmirror/NetflixProvider.kt
- Main plugin implementation showing how to implement a Cloudstream3 plugin
- Demonstrates API calls, JSON parsing, and response formatting
- Shows proper implementation of all required MainAPI methods

### build.gradle.kts (root)
- Configures repositories (Google, Maven Central, JitPack)
- Sets up dependencies including the Cloudstream3 gradle plugin
- Defines shared configurations for all subprojects

### settings.gradle.kts
- Automatically includes all directories as subprojects (plugins)
- Can be modified to include/exclude specific plugins

### gradle.properties
- Contains standard Android/Xamarin Gradle properties
- Includes AndroidX and Jetifier settings

## Cloudstream3 Plugin Development Guidelines

1. **Package naming**: Use a unique package name for each plugin (e.g., `com.yourname.yourplugin`)

2. **Network requests**: Use the provided `app.get()` method for making HTTP requests
   - Always include appropriate headers when needed
   - Use `.parsedSafe<T>()` for safe JSON parsing
   - Handle null responses appropriately

3. **Data modeling**: Use Kotlin data classes with `@JsonIgnoreProperties` for API responses
   - Map API responses to Cloudstream3 model objects using the provided factory methods
   - Factory methods: `newMovieSearchResponse()`, `newTvSeriesSearchResponse()`, `newMovieLoadResponse()`, etc.

4. **Threading**: All plugin methods are suspend functions, so you can use coroutines for asynchronous operations

5. **Error handling**: Throw appropriate exceptions (`ErrorLoadingException`, `ErrorParsingException`, etc.) when errors occur

6. **Resources**: Place plugin-specific resources in `src/main/res/` following Android resource conventions

## Testing

Although this template doesn't include comprehensive tests, you can:
1. Test plugin functionality by deploying to a device via ADB
2. Use Cloudstream3's built-in logging and debugging features
3. Monitor network requests using tools like Charles Proxy or Android Studio's network profiler

## Publishing

To share your plugin with others:
1. Build the plugin AAR using `:make` task
2. Share the generated AAR file located in `build/outputs/aar/`
3. Users can import the AAR into their Cloudstream3 instance

## Troubleshooting

Common issues and solutions:
- **403 errors**: Often require setting proper Referer headers in requests
- **JSON parsing errors**: Ensure your data classes match the API response structure
- **Deployment failures**: Ensure USB debugging is enabled and the device is properly connected
- **Plugin not showing up**: Verify the plugin was built correctly and has a valid name/mainUrl

## Cloudstream3 Specific Notes

- Plugins are loaded dynamically at runtime
- The Cloudstream3 app provides various utility methods through the `app` property
- Always respect the target website's terms of service and rate limits
- Consider implementing caching for frequently accessed data to reduce load on source sites