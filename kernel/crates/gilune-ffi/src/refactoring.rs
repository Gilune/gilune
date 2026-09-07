//! Host-owned journal commands. Providers receive snapshots and return proposals;
//! only the reviewing host receives this apply/recovery authority.
use crate::{FfiResult, Gilune, SourceTextEdit};
use gilune_kernel::refactoring as core;

#[derive(Debug, Clone, uniffi::Record)]
pub struct RefactoringRecoveryCopy {
    pub name: String,
    pub text: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RefactoringPlan {
    pub id: String,
    pub provider: String,
    pub provider_version: String,
    pub configuration: String,
    pub label: String,
    pub state: String,
    pub review_token: Option<String>,
    pub total: u64,
    pub applied: u64,
}
impl From<core::RefactoringPlan> for RefactoringPlan {
    fn from(p: core::RefactoringPlan) -> Self {
        Self {
            id: p.id,
            provider: p.provider,
            provider_version: p.provider_version,
            configuration: p.configuration,
            label: p.label,
            state: p.state,
            review_token: p.review_token,
            total: p.total,
            applied: p.applied,
        }
    }
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct RefactoringEntry {
    pub id: String,
    pub plan_id: String,
    pub rel_path: String,
    pub expected_version: String,
    pub before_text: String,
    pub after_text: String,
    pub state: String,
    pub detail: String,
}
impl From<core::RefactoringEntry> for RefactoringEntry {
    fn from(e: core::RefactoringEntry) -> Self {
        Self {
            id: e.id,
            plan_id: e.plan_id,
            rel_path: e.rel_path,
            expected_version: e.expected_version,
            before_text: e.before_text,
            after_text: e.after_text,
            state: e.state,
            detail: e.detail,
        }
    }
}
#[uniffi::export]
impl Gilune {
    pub fn create_refactoring_plan(
        &self,
        provider: String,
        version: String,
        configuration: String,
        label: String,
    ) -> FfiResult<RefactoringPlan> {
        Ok(self
            .store()
            .create_refactoring_plan(&provider, &version, &configuration, &label)?
            .into())
    }
    pub fn refactoring_plan(&self, id: String) -> FfiResult<RefactoringPlan> {
        Ok(self.store().refactoring_plan(&id)?.into())
    }
    pub fn refactoring_plans(&self) -> FfiResult<Vec<RefactoringPlan>> {
        Ok(self
            .store()
            .refactoring_plans()?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn refactoring_entries(
        &self,
        plan: String,
        offset: u64,
        limit: u32,
    ) -> FfiResult<Vec<RefactoringEntry>> {
        Ok(self
            .store()
            .refactoring_entries(&plan, offset, limit)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn add_refactoring_entry(
        &self,
        plan: String,
        path: String,
        expected: String,
        edits: Vec<SourceTextEdit>,
    ) -> FfiResult<RefactoringEntry> {
        Ok(self
            .store()
            .add_refactoring_entry(
                &plan,
                &path,
                &expected,
                &edits.into_iter().map(Into::into).collect::<Vec<_>>(),
            )?
            .into())
    }
    pub fn add_refactoring_issue(
        &self,
        plan: String,
        path: String,
        detail: String,
    ) -> FfiResult<()> {
        Ok(self.store().add_refactoring_issue(&plan, &path, &detail)?)
    }
    pub fn seal_refactoring_plan(&self, plan: String) -> FfiResult<RefactoringPlan> {
        Ok(self.store().seal_refactoring_plan(&plan)?.into())
    }
    pub fn set_refactoring_cancelled(&self, plan: String, cancelled: bool) -> FfiResult<()> {
        Ok(self.store().set_refactoring_cancelled(&plan, cancelled)?)
    }
    pub fn apply_refactoring_entry(
        &self,
        plan: String,
        token: String,
        entry: String,
    ) -> FfiResult<RefactoringEntry> {
        Ok(self
            .store()
            .apply_refactoring_entry(&plan, &token, &entry)?
            .into())
    }
    pub fn recover_refactorings(&self) -> FfiResult<()> {
        Ok(self.store().recover_refactorings()?)
    }
    pub fn prepare_refactoring_undo(&self, original: String) -> FfiResult<RefactoringPlan> {
        Ok(self.store().prepare_refactoring_undo(&original)?.into())
    }
    pub fn refactoring_recovery_copies(
        &self,
        entry: String,
    ) -> FfiResult<Vec<RefactoringRecoveryCopy>> {
        Ok(self
            .store()
            .refactoring_recovery_copies(&entry)?
            .into_iter()
            .map(|(name, text)| RefactoringRecoveryCopy { name, text })
            .collect())
    }
    pub fn prepare_refactoring_recovery(
        &self,
        entry: String,
        name: String,
    ) -> FfiResult<RefactoringPlan> {
        Ok(self
            .store()
            .prepare_refactoring_recovery(&entry, &name)?
            .into())
    }
}
