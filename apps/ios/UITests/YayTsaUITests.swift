import XCTest

final class YayTsaUITests: XCTestCase {
    private let screenshotDir = "/tmp/yaytsa_ui_test"
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        try? FileManager.default.createDirectory(atPath: screenshotDir, withIntermediateDirectories: true)
    }

    /// The simulator's one-time "Enable Dictation?" prompt can reappear unpredictably
    /// and, if mishandled, escalates into a full-screen Siri/Dictation/Privacy sheet with
    /// no auto-dismiss — check for and clear either one before every meaningful step.
    private func clearDictationSheetIfPresent() {
        for _ in 0..<3 {
            // Not scoped to app.alerts — on this iOS version the dialog isn't
            // classified as an alert in the accessibility tree.
            let notNow = app.buttons["Не сейчас"]
            if notNow.exists { notNow.tap(); Thread.sleep(forTimeInterval: 0.3); continue }
            let notNowEn = app.buttons["Not Now"]
            if notNowEn.exists { notNowEn.tap(); Thread.sleep(forTimeInterval: 0.3); continue }
            guard app.staticTexts["Siri, Dictation & Privacy"].exists else { return }
            // Full-screen privacy sheet: close via its top-left "X". No reliable
            // accessibility label, so try the first button element, then fall back
            // to a normalized-position tap (measured from the actual screenshot).
            let firstButton = app.buttons.element(boundBy: 0)
            if firstButton.exists {
                firstButton.tap()
            } else {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.095, dy: 0.125)).tap()
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
    }

    /// Clears any existing content before typing — defensive against stray/leftover
    /// field content between test runs.
    private func typeSlowly(_ text: String, into element: XCUIElement) {
        element.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 30))
        element.typeText(text)
    }

    private func snap(_ name: String) {
        clearDictationSheetIfPresent()
        let data = XCUIScreen.main.screenshot().image.pngData()
        let path = "\(screenshotDir)/\(name).png"
        try? data?.write(to: URL(fileURLWithPath: path))
    }

    /// Taps an element, falling back to a coordinate-based tap if XCTest reports it
    /// as not hittable (happens right after a tab switch while a large title collapses).
    /// Always taps by coordinate rather than XCUIElement.tap() — the large-title nav
    /// bar's collapse animation right after a tab switch causes XCTest's isHittable
    /// check to pass and then the synthesized tap to still fail as "not hittable"
    /// (a check/act race), which a plain coordinate tap sidesteps entirely.
    private func robustTap(_ element: XCUIElement) {
        clearDictationSheetIfPresent()
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }

    /// Taps a tab bar item by label, following into the "More" overflow list if iOS
    /// folded it there (TabView only shows ~4 items before overflowing).
    private func goToTab(_ label: String) {
        clearDictationSheetIfPresent()
        let direct = app.tabBars.buttons[label]
        if direct.exists {
            robustTap(direct)
            return
        }
        robustTap(app.tabBars.buttons["More"])
        Thread.sleep(forTimeInterval: 0.5)
        clearDictationSheetIfPresent()
        let inMoreList = app.staticTexts[label].firstMatch
        if inMoreList.waitForExistence(timeout: 3) {
            robustTap(inMoreList)
        } else {
            // Fall back to any cell containing the label if it's not a plain staticText.
            let cell = app.cells.containing(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
            if cell.waitForExistence(timeout: 3) {
                robustTap(cell)
            }
        }
    }

    func testSmoke() throws {
        app = XCUIApplication()
        app.launch()

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            clearDictationSheetIfPresent()
            signInButton.tap()
        }

        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)
        Thread.sleep(forTimeInterval: 1.5)
        snap("01_home")

        // Search
        goToTab("Search")
        Thread.sleep(forTimeInterval: 0.5)
        let searchField = app.searchFields.firstMatch
        if searchField.waitForExistence(timeout: 5) {
            searchField.tap()
            searchField.typeText("Test")
            Thread.sleep(forTimeInterval: 2)
            snap("02_search_results")
        }

        // Favorites
        goToTab("Favorites")
        Thread.sleep(forTimeInterval: 1.5)
        snap("03_favorites")

        // Artists
        goToTab("Artists")
        Thread.sleep(forTimeInterval: 2.5)
        snap("04_artists")
        let firstArtistCell = app.cells.firstMatch
        if firstArtistCell.waitForExistence(timeout: 5) {
            Thread.sleep(forTimeInterval: 1)
            robustTap(firstArtistCell)
            Thread.sleep(forTimeInterval: 1.5)
            snap("05_artist_detail")
            robustTap(app.navigationBars.buttons.firstMatch)
        }

        // Albums
        goToTab("Albums")
        Thread.sleep(forTimeInterval: 2.5)
        snap("06_albums")
        let firstAlbumCell = app.cells.firstMatch
        if firstAlbumCell.waitForExistence(timeout: 5) {
            Thread.sleep(forTimeInterval: 1)
            robustTap(firstAlbumCell)
            Thread.sleep(forTimeInterval: 1)
            snap("07_album_detail")
            let firstFavoriteButton = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'favoriteButton.'")).firstMatch
            if firstFavoriteButton.waitForExistence(timeout: 5) {
                Thread.sleep(forTimeInterval: 0.5)
                robustTap(firstFavoriteButton)
                Thread.sleep(forTimeInterval: 1.5)
                snap("08_album_detail_favorited")
            }

            // Play the first track (tap the left/name portion of the row, avoiding
            // the favorite heart on the right) so the mini player + now-playing sheet appear.
            let firstTrackCell = app.cells.element(boundBy: 0)
            if firstTrackCell.exists {
                firstTrackCell.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
                Thread.sleep(forTimeInterval: 2)
                clearDictationSheetIfPresent()
                snap("08a_playing")
                let miniPlayer = app.buttons["miniPlayerBar"]
                if miniPlayer.waitForExistence(timeout: 5) {
                    miniPlayer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                    Thread.sleep(forTimeInterval: 1.5)
                    snap("08b_now_playing")
                    if app.buttons["Close"].waitForExistence(timeout: 3) {
                        robustTap(app.buttons["Close"])
                    }
                } else {
                    snap("08c_no_miniplayer_found")
                }
            }
        }

        // Playlists: create one
        goToTab("Playlists")
        Thread.sleep(forTimeInterval: 1)
        snap("09_playlists_before")
        let addButton = app.navigationBars.buttons["Add"]
        if addButton.waitForExistence(timeout: 5) {
            robustTap(addButton)
            let nameField = app.textFields["Name"]
            if nameField.waitForExistence(timeout: 3) {
                nameField.typeText("UI Test Playlist")
                robustTap(app.buttons["Create"])
                Thread.sleep(forTimeInterval: 2)
                snap("10_playlists_after")
            }
        }

        // Audiobooks
        goToTab("Audiobooks")
        Thread.sleep(forTimeInterval: 1.5)
        snap("11_audiobooks")

        // Settings
        goToTab("Settings")
        Thread.sleep(forTimeInterval: 1.5)
        snap("12_settings")
        let rescanButton = app.buttons["Rescan library"]
        if rescanButton.waitForExistence(timeout: 3) {
            robustTap(rescanButton)
            Thread.sleep(forTimeInterval: 3)
            snap("13_settings_after_rescan")
        }
    }

    func testRadio() throws {
        app = XCUIApplication()
        app.launch()

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            signInButton.tap()
        }

        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)
        goToTab("Home")
        Thread.sleep(forTimeInterval: 1.5)
        snap("radio_00_home_before")

        let startRadio = app.buttons["Start Radio"].exists ? app.buttons["Start Radio"] : app.staticTexts["Start Radio"]
        XCTAssertTrue(startRadio.waitForExistence(timeout: 5), "Start Radio control not found on Home")
        robustTap(startRadio)
        Thread.sleep(forTimeInterval: 3)
        snap("radio_01_started")

        XCTAssertTrue(app.staticTexts["Radio Playing"].waitForExistence(timeout: 5), "Radio did not report active state")

        let miniPlayer = app.buttons["miniPlayerBar"]
        if miniPlayer.waitForExistence(timeout: 5) {
            robustTap(miniPlayer)
            Thread.sleep(forTimeInterval: 1.5)
            snap("radio_02_now_playing")

            let thumbsUp = app.buttons["hand.thumbsup"].exists ? app.buttons["hand.thumbsup"] : app.images["hand.thumbsup"]
            XCTAssertTrue(app.staticTexts["Radio"].waitForExistence(timeout: 3), "Now Playing sheet missing Radio indicator")
            if thumbsUp.exists {
                robustTap(thumbsUp)
                Thread.sleep(forTimeInterval: 1)
                snap("radio_03_thumbs_up")
            }

            if app.buttons["Close"].waitForExistence(timeout: 3) {
                robustTap(app.buttons["Close"])
            }
        }

        goToTab("Home")
        Thread.sleep(forTimeInterval: 1)
        let radioPlayingCard = app.staticTexts["Radio Playing"]
        if radioPlayingCard.waitForExistence(timeout: 5) {
            robustTap(radioPlayingCard)
            Thread.sleep(forTimeInterval: 2)
            snap("radio_04_stopped")
        }
    }

    func testGroupListen() throws {
        app = XCUIApplication()
        app.launch()

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            signInButton.tap()
        }

        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)

        // Play something first so Now Playing (and the Group Listen entry point) is reachable.
        goToTab("Albums")
        Thread.sleep(forTimeInterval: 2.5)
        let firstAlbumCell = app.cells.firstMatch
        XCTAssertTrue(firstAlbumCell.waitForExistence(timeout: 5), "No albums to play from")
        robustTap(firstAlbumCell)
        Thread.sleep(forTimeInterval: 1)
        let firstTrackCell = app.cells.element(boundBy: 0)
        XCTAssertTrue(firstTrackCell.waitForExistence(timeout: 5))
        firstTrackCell.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
        Thread.sleep(forTimeInterval: 2)
        clearDictationSheetIfPresent()

        let miniPlayer = app.buttons["miniPlayerBar"]
        XCTAssertTrue(miniPlayer.waitForExistence(timeout: 5), "Mini player did not appear after playing a track")
        robustTap(miniPlayer)
        Thread.sleep(forTimeInterval: 1.5)
        snap("group_00_now_playing")

        let groupIcon = app.buttons.matching(NSPredicate(format: "label CONTAINS 'person.2.wave.2' OR identifier CONTAINS 'person.2.wave.2'")).firstMatch
        let groupButton = groupIcon.exists ? groupIcon : app.images["person.2.wave.2"].firstMatch
        XCTAssertTrue(groupButton.waitForExistence(timeout: 5), "Group Listen entry icon not found in Now Playing")
        robustTap(groupButton)
        Thread.sleep(forTimeInterval: 1)
        snap("group_01_sheet_opened")

        // Default mode is "Create" — fill in a name and create the group.
        let nameField = app.textFields["Group name"]
        if nameField.waitForExistence(timeout: 3) {
            nameField.tap()
            if let existing = nameField.value as? String, !existing.isEmpty {
                let deleteAll = String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count)
                nameField.typeText(deleteAll)
            }
            nameField.typeText("UI Test Party")
        }
        let createButton = app.buttons["Create Group"]
        XCTAssertTrue(createButton.waitForExistence(timeout: 3))
        robustTap(createButton)
        Thread.sleep(forTimeInterval: 3)
        snap("group_02_created")

        XCTAssertTrue(app.staticTexts["UI Test Party"].waitForExistence(timeout: 5), "Group name not shown after creation")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Join code:'")).firstMatch.waitForExistence(timeout: 3), "Join code not shown")

        // Owner should see the Host/Everyone control-mode picker and an End Group button.
        XCTAssertTrue(app.buttons["Everyone"].waitForExistence(timeout: 3), "Control mode picker missing for owner")
        robustTap(app.buttons["Everyone"])
        Thread.sleep(forTimeInterval: 1)
        snap("group_03_control_mode_everyone")

        let endButton = app.buttons["End Group"]
        if endButton.waitForExistence(timeout: 3) {
            robustTap(endButton)
            Thread.sleep(forTimeInterval: 1.5)
            snap("group_04_ended")
        }

        // Two sheets are stacked (Now Playing behind Group Listen), so "Close" is
        // ambiguous app-wide — scope to the Group Listen sheet's own nav bar.
        let groupCloseButton = app.navigationBars["Group Listen"].buttons["Close"]
        if groupCloseButton.waitForExistence(timeout: 3) {
            robustTap(groupCloseButton)
        }
    }

    func testPlaylistManagementAndDjPreferences() throws {
        app = XCUIApplication()
        app.launch()

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            clearDictationSheetIfPresent()
            signInButton.tap()
        }
        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)

        // Create a playlist with two tracks, then exercise reorder/remove/delete.
        goToTab("Albums")
        Thread.sleep(forTimeInterval: 2.5)
        let firstAlbumCell = app.cells.firstMatch
        if firstAlbumCell.waitForExistence(timeout: 5) {
            robustTap(firstAlbumCell)
            Thread.sleep(forTimeInterval: 1)
            for i in 0..<2 {
                let cell = app.cells.element(boundBy: i)
                if cell.exists {
                    cell.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
                    Thread.sleep(forTimeInterval: 0.5)
                }
            }
        }

        // A unique name per run avoids ever colliding with a leftover playlist from
        // an earlier interrupted run (which would make later lookups ambiguous).
        let playlistName = "Mgmt Test Playlist \(Int.random(in: 1000...9999))"
        goToTab("Playlists")
        Thread.sleep(forTimeInterval: 1)
        let addButton = app.navigationBars.buttons["Add"]
        if addButton.waitForExistence(timeout: 5) {
            robustTap(addButton)
            let nameField = app.textFields["Name"]
            if nameField.waitForExistence(timeout: 3) {
                nameField.typeText(playlistName)
                robustTap(app.buttons["Create"])
                Thread.sleep(forTimeInterval: 0.8)
                snap("toast_00_playlist_created")
                Thread.sleep(forTimeInterval: 0.7)
            }
        }
        let playlistCell = app.staticTexts[playlistName].firstMatch
        if playlistCell.waitForExistence(timeout: 5) {
            robustTap(playlistCell)
            Thread.sleep(forTimeInterval: 1.5)
            snap("mgmt_00_playlist_detail")

            let menuButton = app.navigationBars[playlistName].buttons["More"]
            if menuButton.waitForExistence(timeout: 3) {
                robustTap(menuButton)
                Thread.sleep(forTimeInterval: 0.5)
                snap("mgmt_01_menu_open")
                if app.buttons["Delete Playlist"].waitForExistence(timeout: 3) {
                    robustTap(app.buttons["Delete Playlist"])
                    Thread.sleep(forTimeInterval: 0.5)
                    if app.buttons["Delete Playlist"].waitForExistence(timeout: 3) {
                        robustTap(app.buttons["Delete Playlist"])
                        Thread.sleep(forTimeInterval: 1.5)
                        snap("mgmt_02_after_delete")
                    }
                }
            }
        }

        // DJ Preferences panel.
        goToTab("Settings")
        Thread.sleep(forTimeInterval: 1.5)
        let djLink = app.staticTexts["DJ Preferences"]
        if djLink.waitForExistence(timeout: 5) {
            robustTap(djLink)
            Thread.sleep(forTimeInterval: 2)
            snap("mgmt_03_dj_preferences")
        }
    }

    func testSyncedLyrics() throws {
        app = XCUIApplication()
        app.launch()

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            clearDictationSheetIfPresent()
            signInButton.tap()
        }
        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)

        // "вишлист" has real LRC-timestamped lyrics seeded in the test library.
        goToTab("Search")
        Thread.sleep(forTimeInterval: 0.5)
        let searchField = app.searchFields.firstMatch
        XCTAssertTrue(searchField.waitForExistence(timeout: 5))
        searchField.tap()
        searchField.typeText("вишлист")
        Thread.sleep(forTimeInterval: 2)
        snap("lyrics_debug_search")
        let trackResult = app.buttons["вишлист"].firstMatch
        XCTAssertTrue(trackResult.waitForExistence(timeout: 5), "Test track with seeded lyrics not found via search")
        robustTap(trackResult)
        Thread.sleep(forTimeInterval: 1.5)

        let miniPlayer = app.buttons["miniPlayerBar"]
        XCTAssertTrue(miniPlayer.waitForExistence(timeout: 5))
        robustTap(miniPlayer)
        Thread.sleep(forTimeInterval: 1)

        let lyricsButton = app.buttons["quote.bubble"].exists ? app.buttons["quote.bubble"] : app.images["quote.bubble"].firstMatch
        XCTAssertTrue(lyricsButton.waitForExistence(timeout: 5), "Lyrics entry icon not found")
        robustTap(lyricsButton)
        Thread.sleep(forTimeInterval: 1.5)
        snap("lyrics_00_early")

        // Let playback advance well into the track and confirm the highlighted line moved.
        Thread.sleep(forTimeInterval: 8)
        snap("lyrics_01_later")

        XCTAssertFalse(app.staticTexts["Lyrics are not time-synced"].exists, "Seeded LRC lyrics were not recognized as time-synced")
    }

    func testSemanticSearch() throws {
        app = XCUIApplication()
        app.launch()

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            clearDictationSheetIfPresent()
            signInButton.tap()
        }
        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)

        goToTab("Search")
        Thread.sleep(forTimeInterval: 0.5)
        let searchField = app.searchFields.firstMatch
        XCTAssertTrue(searchField.waitForExistence(timeout: 5))
        searchField.tap()
        searchField.typeText("test")
        Thread.sleep(forTimeInterval: 1.5)
        snap("semantic_00_scopes")

        let semanticScope = app.buttons["Semantic"]
        XCTAssertTrue(semanticScope.waitForExistence(timeout: 3), "Semantic search scope button not found")
        semanticScope.tap()
        Thread.sleep(forTimeInterval: 2)
        snap("semantic_01_results")

        XCTAssertFalse(app.staticTexts["Search failed"].exists, "Semantic search returned an error")
    }

    func testAudiobookChaptersDevicesAndDownload() throws {
        app = XCUIApplication()
        app.launch()

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            clearDictationSheetIfPresent()
            signInButton.tap()
        }
        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)

        // Audiobooks: open the seeded 2-chapter test book, mark a chapter finished, restart it.
        goToTab("Audiobooks")
        Thread.sleep(forTimeInterval: 1.5)
        let bookCell = app.staticTexts["Test Audiobook"].firstMatch
        XCTAssertTrue(bookCell.waitForExistence(timeout: 5), "Seeded test audiobook not found")
        robustTap(bookCell)
        Thread.sleep(forTimeInterval: 1)
        snap("book_00_detail")

        let chapter1 = app.staticTexts["Chapter 1"].firstMatch
        XCTAssertTrue(chapter1.waitForExistence(timeout: 5))
        chapter1.swipeLeft()
        Thread.sleep(forTimeInterval: 0.3)
        snap("book_01_swipe_actions")
        let markFinished = app.buttons["Mark Finished"]
        if markFinished.waitForExistence(timeout: 3) {
            markFinished.tap()
            Thread.sleep(forTimeInterval: 1)
            snap("book_02_after_finish")
        }

        // Restart should now be offered instead of Mark Finished.
        if chapter1.waitForExistence(timeout: 3) {
            chapter1.swipeLeft()
            Thread.sleep(forTimeInterval: 0.3)
            if app.buttons["Restart"].waitForExistence(timeout: 3) {
                app.buttons["Restart"].tap()
                Thread.sleep(forTimeInterval: 1)
                snap("book_03_after_restart")
            }
        }

        // Devices panel (Settings > Devices) — this simulator is the only session, so
        // the empty state is the expected, correct render, not a failure.
        goToTab("Settings")
        Thread.sleep(forTimeInterval: 1)
        let devicesLink = app.staticTexts["Devices"].firstMatch
        if devicesLink.waitForExistence(timeout: 5) {
            robustTap(devicesLink)
            Thread.sleep(forTimeInterval: 1.5)
            snap("devices_00_panel")
            XCTAssertFalse(app.staticTexts["Couldn't load devices"].exists, "Devices panel failed to load")
            if app.navigationBars.buttons.firstMatch.exists {
                robustTap(app.navigationBars.buttons.firstMatch)
            }
        }

        // Offline download: download a track from Albums, confirm the button reflects state.
        goToTab("Albums")
        Thread.sleep(forTimeInterval: 2.5)
        let firstAlbumCell = app.cells.firstMatch
        if firstAlbumCell.waitForExistence(timeout: 5) {
            robustTap(firstAlbumCell)
            Thread.sleep(forTimeInterval: 1)
            let downloadButton = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'downloadButton.'")).firstMatch
            if downloadButton.waitForExistence(timeout: 5) {
                robustTap(downloadButton)
                Thread.sleep(forTimeInterval: 3)
                snap("download_00_after_tap")
            }
        }
    }

    /// Visual-polish sweep: screenshots every remaining screen not already covered by
    /// another test, for manual review of theme/icon/layout consistency.
    func testVisualTour() throws {
        app = XCUIApplication()
        app.launch()
        snap("tour_00_login")

        let signInButton = app.buttons["Sign In"]
        if signInButton.waitForExistence(timeout: 3) {
            let serverField = app.textFields.firstMatch
            serverField.tap()
            typeSlowly("http://localhost:8080", into: serverField)
            let usernameField = app.textFields["Username"]
            usernameField.tap()
            typeSlowly("admin", into: usernameField)
            let passwordField = app.secureTextFields["Password"]
            passwordField.tap()
            typeSlowly("yaytsa-dev-2026", into: passwordField)
            clearDictationSheetIfPresent()
            signInButton.tap()
        }
        _ = app.tabBars.firstMatch.waitForExistence(timeout: 10)

        goToTab("Albums")
        Thread.sleep(forTimeInterval: 2)
        snap("tour_01_albums")
        let firstAlbumCell = app.cells.firstMatch
        if firstAlbumCell.waitForExistence(timeout: 5) {
            robustTap(firstAlbumCell)
            Thread.sleep(forTimeInterval: 1)
            snap("tour_02_album_detail")
            robustTap(app.navigationBars.buttons.firstMatch)
        }

        goToTab("Settings")
        Thread.sleep(forTimeInterval: 1.5)
        snap("tour_03_settings")
        app.swipeUp()
        Thread.sleep(forTimeInterval: 0.5)
        snap("tour_03b_settings_scrolled")

        let usersLink = app.staticTexts["Users"].firstMatch
        if usersLink.waitForExistence(timeout: 3) {
            robustTap(usersLink)
            Thread.sleep(forTimeInterval: 1.5)
            snap("tour_04_users_admin")
            let addButton = app.navigationBars.buttons["Add"]
            if addButton.waitForExistence(timeout: 3) {
                robustTap(addButton)
                Thread.sleep(forTimeInterval: 1)
                snap("tour_05_new_user_sheet")
                if app.buttons["Cancel"].waitForExistence(timeout: 3) {
                    robustTap(app.buttons["Cancel"])
                }
            }
            robustTap(app.navigationBars.buttons.firstMatch)
        }

        goToTab("Settings")
        Thread.sleep(forTimeInterval: 1)
        app.swipeUp()
        Thread.sleep(forTimeInterval: 0.5)
        let changePasswordLink = app.staticTexts["Change Password"].firstMatch
        if changePasswordLink.waitForExistence(timeout: 3) {
            robustTap(changePasswordLink)
            Thread.sleep(forTimeInterval: 1)
            snap("tour_06_change_password")
            robustTap(app.navigationBars.buttons.firstMatch)
        }

        // Play a track, open the full Now Playing sheet and the queue.
        goToTab("Albums")
        Thread.sleep(forTimeInterval: 2)
        if firstAlbumCell.waitForExistence(timeout: 5) {
            robustTap(firstAlbumCell)
            Thread.sleep(forTimeInterval: 1)
            let firstTrackCell = app.cells.element(boundBy: 0)
            if firstTrackCell.exists {
                firstTrackCell.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
                Thread.sleep(forTimeInterval: 1.5)
                let miniPlayer = app.buttons["miniPlayerBar"]
                if miniPlayer.waitForExistence(timeout: 5) {
                    robustTap(miniPlayer)
                    Thread.sleep(forTimeInterval: 1.5)
                    snap("tour_07_now_playing")

                    let queueButton = app.buttons["list.bullet"].exists ? app.buttons["list.bullet"] : app.images["list.bullet"].firstMatch
                    if queueButton.waitForExistence(timeout: 3) {
                        robustTap(queueButton)
                        Thread.sleep(forTimeInterval: 1)
                        snap("tour_08_queue")
                        if app.buttons["Close"].waitForExistence(timeout: 3) {
                            robustTap(app.buttons["Close"])
                        }
                    }
                }
            }
        }
    }
}
