-- SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- "Uninstall njMule", beside the app in its disk image: moves njMule.app to the Trash and, when the box is ticked,
-- the user's profile of njMule too. The completed downloads stay where they are. A njMule still running saves and
-- stops first.
on run
    set appId to "org.njmule.njMule"
    set dialogTitle to "Uninstall njMule"
    set profileBox to "Delete my njMule data: settings, server and contact lists, identity and credits, unfinished downloads and logs"
    set chosen to choose from list {profileBox} with title dialogTitle with prompt "njMule goes to the Trash, and closes first if it runs. Select the line below to also delete your data; leave it unselected to keep it for a later installation. Your completed downloads are always kept." OK button name "Uninstall" cancel button name "Cancel" with empty selection allowed
    if chosen is false then return
    set gui to missing value
    try
        set gui to quoted form of ((POSIX path of (path to application id appId)) & "Contents/MacOS/njmule-gui")
    end try
    -- The engine waits for the njMule on the user's profile to save and stop; its window then ends with it.
    if gui is not missing value then
        try
            do shell script gui & " --exit"
        end try
    end if
    repeat 20 times
        if application id appId is not running then exit repeat
        delay 0.5
    end repeat
    if application id appId is running then
        display dialog "njMule still runs: quit it, then uninstall it again." buttons {"OK"} default button 1 with title dialogTitle with icon caution
        return
    end if
    -- What njMule set up for the user goes first: the ed2k links back to their previous program, no start at login.
    if gui is not missing value then
        try
            do shell script gui & " --restore-links"
        end try
    end if
    set doomed to {}
    try
        set end of doomed to (path to application id appId)
    end try
    if chosen is not {} then
        set homePath to POSIX path of (path to home folder)
        repeat with profilePath in {"Library/Application Support/njMule", "Library/Caches/org.njmule.njMule", "Library/Logs/njMule"}
            try
                set end of doomed to (POSIX file (homePath & (contents of profilePath)) as alias)
            end try
        end repeat
    end if
    if doomed is {} then return
    tell application "Finder" to delete doomed
    display dialog "njMule is uninstalled." buttons {"OK"} default button 1 with title dialogTitle
end run
