// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's FileCommentsTest, under its names: our ratings and comments of files in `fileinfo.ini`.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::PathBuf;

use njmule_engine::api::file_comment::FileComment;
use njmule_engine::files::file_comments::{self, FileComments};

const FILM: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
const SONG: [u8; 16] = [0; 16];

#[test]
fn should_read_the_comments_of_an_imported_profile() {
    let mut test = Comments::new();
    test.given_the_comments_file_of_an_imported_profile();

    test.when_the_comments_are_loaded();

    test.then_the_comment_of_is(&FILM, FileComment::new("Great città", FileComment::MAX_RATING).unwrap());
}

#[test]
fn should_keep_our_comment_on_an_imported_profile() {
    let mut test = Comments::new();
    test.given_the_comments_file_of_an_imported_profile();
    test.given_the_comments_are_loaded();

    test.when_we_comment_the_file(&SONG, "Nice\nline", 3);

    test.then_the_saved_comment_of_is(&SONG, FileComment::new("Nice line", 3).unwrap());
}

#[test]
fn should_forget_our_comment_once_removed() {
    let mut test = Comments::new();
    test.given_the_comments_file_of_an_imported_profile();
    test.given_the_comments_are_loaded();
    test.given_we_commented_the_file(&SONG, "Nice\nline", 3);

    test.when_we_remove_the_comment_of(&SONG);

    test.then_no_comment_is_saved_for(&SONG);
}

#[test]
fn should_have_no_comments_without_a_file() {
    let mut test = Comments::new();

    test.when_the_comments_are_loaded_from("none");

    test.then_there_is_no_comment_for(&FILM);
}

#[test]
fn should_read_a_missing_file_from_its_backup() {
    let mut test = Comments::new();
    test.given_the_comments_are_loaded();
    test.given_we_commented_the_file(&FILM, "kept", 4);
    test.given_we_commented_the_file(&FILM, "changed", 5);
    test.given_the_file_is_deleted();

    test.when_the_comments_are_loaded();

    test.then_the_comment_of_is(&FILM, FileComment::of("kept", 4));
}

struct Comments {
    dir: tempfile::TempDir,
    file: PathBuf,
    comments: Option<FileComments>,
}

impl Comments {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join(file_comments::FILE_NAME);
        Self { dir, file, comments: None }
    }

    fn given_the_comments_file_of_an_imported_profile(&self) {
        let text =
            "[0123456789ABCDEF0123456789ABCDEF]\r\nComment=Great città\r\nRate=9\r\n[not a hash]\r\nComment=x\r\n";
        fs::write(&self.file, text).unwrap();
    }

    fn given_the_comments_are_loaded(&mut self) {
        self.when_the_comments_are_loaded();
    }

    fn given_we_commented_the_file(&mut self, hash: &[u8; 16], text: &str, rating: u8) {
        self.when_we_comment_the_file(hash, text, rating);
    }

    fn given_the_file_is_deleted(&self) {
        fs::remove_file(&self.file).unwrap();
    }

    fn when_the_comments_are_loaded(&mut self) {
        self.comments = Some(FileComments::load(&self.file).unwrap());
    }

    fn when_the_comments_are_loaded_from(&mut self, file_name: &str) {
        self.comments = Some(FileComments::load(&self.dir.path().join(file_name)).unwrap());
    }

    fn when_we_comment_the_file(&mut self, hash: &[u8; 16], text: &str, rating: u8) {
        self.comments().set(hash, FileComment::of(text, rating)).unwrap();
    }

    fn when_we_remove_the_comment_of(&mut self, hash: &[u8; 16]) {
        self.comments().set(hash, FileComment::NONE).unwrap();
    }

    fn then_the_comment_of_is(&mut self, hash: &[u8; 16], expected: FileComment) {
        assert_eq!(self.comments().get(hash), Some(expected));
    }

    fn then_there_is_no_comment_for(&mut self, hash: &[u8; 16]) {
        assert_eq!(self.comments().get(hash), None);
    }

    fn then_the_saved_comment_of_is(&self, hash: &[u8; 16], expected: FileComment) {
        assert_eq!(FileComments::load(&self.file).unwrap().get(hash), Some(expected));
    }

    fn then_no_comment_is_saved_for(&self, hash: &[u8; 16]) {
        assert_eq!(FileComments::load(&self.file).unwrap().get(hash), None);
    }

    fn comments(&mut self) -> &mut FileComments {
        self.comments.as_mut().expect("the comments loaded")
    }
}
