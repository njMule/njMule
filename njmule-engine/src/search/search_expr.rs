// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A boolean search expression. `NOT` is binary: `a NOT b` matches `a` without `b`.

/// Comparison operators of numeric attributes, with their wire values.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Comparison {
    Equal,
    Greater,
    Less,
    GreaterEqual,
    LessEqual,
    NotEqual,
}

impl Comparison {
    pub fn code(self) -> u8 {
        match self {
            Comparison::Equal => 0,
            Comparison::Greater => 1,
            Comparison::Less => 2,
            Comparison::GreaterEqual => 3,
            Comparison::LessEqual => 4,
            Comparison::NotEqual => 5,
        }
    }

    pub fn symbol(self) -> &'static str {
        match self {
            Comparison::Equal => "=",
            Comparison::Greater => ">",
            Comparison::Less => "<",
            Comparison::GreaterEqual => ">=",
            Comparison::LessEqual => "<=",
            Comparison::NotEqual => "<>",
        }
    }

    pub fn of(code: u8) -> Option<Self> {
        Some(match code {
            0 => Comparison::Equal,
            1 => Comparison::Greater,
            2 => Comparison::Less,
            3 => Comparison::GreaterEqual,
            4 => Comparison::LessEqual,
            5 => Comparison::NotEqual,
            _ => return None,
        })
    }

    /// The name the Java engine and the corpora know the comparison by.
    pub fn name(self) -> &'static str {
        match self {
            Comparison::Equal => "EQUAL",
            Comparison::Greater => "GREATER",
            Comparison::Less => "LESS",
            Comparison::GreaterEqual => "GREATER_EQUAL",
            Comparison::LessEqual => "LESS_EQUAL",
            Comparison::NotEqual => "NOT_EQUAL",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum SearchExpr {
    And(Box<SearchExpr>, Box<SearchExpr>),
    Or(Box<SearchExpr>, Box<SearchExpr>),
    Not(Box<SearchExpr>, Box<SearchExpr>),
    /// A word or a quoted phrase (quotes kept) matched against file names; never empty.
    Keyword(String),
    /// A string attribute such as the file type or extension (a file tag id).
    StringAttr {
        tag: u8,
        value: String,
    },
    /// A numeric attribute such as the size or the number of sources (a file tag id), below 2^63.
    NumericAttr {
        tag: u8,
        comparison: Comparison,
        value: u64,
    },
}

impl SearchExpr {
    pub fn and(left: SearchExpr, right: SearchExpr) -> Self {
        SearchExpr::And(Box::new(left), Box::new(right))
    }

    pub fn or(left: SearchExpr, right: SearchExpr) -> Self {
        SearchExpr::Or(Box::new(left), Box::new(right))
    }

    pub fn not(left: SearchExpr, right: SearchExpr) -> Self {
        SearchExpr::Not(Box::new(left), Box::new(right))
    }

    pub fn keyword(text: &str) -> Self {
        SearchExpr::Keyword(text.to_owned())
    }
}
