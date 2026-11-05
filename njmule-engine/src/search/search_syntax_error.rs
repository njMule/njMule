// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! An invalid search expression; its reason lets front ends show a translated message.

use std::fmt;

/// Why the expression was refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Reason {
    General,
    MissingAndOperand,
    MissingOrOperand,
    MissingNotOperand,
    MissingExpressionInParentheses,
    MissingClosingParenthesis,
    UnknownAttribute,
    InvalidAttributeValue,
    OperatorWithoutAttribute,
    UnterminatedString,
    TooComplex,
    InvalidEd2kLink,
}

impl Reason {
    /// The name front ends and the Java engine know the reason by.
    pub fn name(self) -> &'static str {
        match self {
            Reason::General => "GENERAL",
            Reason::MissingAndOperand => "MISSING_AND_OPERAND",
            Reason::MissingOrOperand => "MISSING_OR_OPERAND",
            Reason::MissingNotOperand => "MISSING_NOT_OPERAND",
            Reason::MissingExpressionInParentheses => "MISSING_EXPRESSION_IN_PARENTHESES",
            Reason::MissingClosingParenthesis => "MISSING_CLOSING_PARENTHESIS",
            Reason::UnknownAttribute => "UNKNOWN_ATTRIBUTE",
            Reason::InvalidAttributeValue => "INVALID_ATTRIBUTE_VALUE",
            Reason::OperatorWithoutAttribute => "OPERATOR_WITHOUT_ATTRIBUTE",
            Reason::UnterminatedString => "UNTERMINATED_STRING",
            Reason::TooComplex => "TOO_COMPLEX",
            Reason::InvalidEd2kLink => "INVALID_ED2K_LINK",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchSyntaxError {
    reason: Reason,
    position: usize,
    detail: String,
}

impl SearchSyntaxError {
    pub(crate) fn new(reason: Reason, position: usize, detail: impl Into<String>) -> Self {
        Self { reason, position, detail: detail.into() }
    }

    pub fn reason(&self) -> Reason {
        self.reason
    }

    /// Character offset in the expression where the problem was found.
    pub fn position(&self) -> usize {
        self.position
    }
}

impl fmt::Display for SearchSyntaxError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} at {}", self.reason.name(), self.position)?;
        if !self.detail.is_empty() {
            write!(f, ": {}", self.detail)?;
        }
        Ok(())
    }
}

impl std::error::Error for SearchSyntaxError {}
