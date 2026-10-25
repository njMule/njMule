-- SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- "Uninstall njMule", beside the app in its disk image: moves njMule.app to the Trash and, when the box is ticked,
-- the user's profile of njMule too. The completed downloads stay where they are.
on run
    set appId to "org.njmule.njMule"
    set dialogTitle to "Uninstall njMule"
    if application id appId is running then
        display dialog "Quit njMule first, then uninstall it." buttons {"OK"} default button 1 with title dialogTitle with icon caution
        return
    end if
    set profileBox to "Delete my njMule data: settings, server and contact lists, identity and credits, unfinished downloads and logs"
    set chosen to choose from list {profileBox} with title dialogTitle with prompt "njMule goes to the Trash. Select the line below to also delete your data; leave it unselected to keep it for a later installation. Your completed downloads are always kept." OK button name "Uninstall" cancel button name "Cancel" with empty selection allowed
    if chosen is false then return
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
