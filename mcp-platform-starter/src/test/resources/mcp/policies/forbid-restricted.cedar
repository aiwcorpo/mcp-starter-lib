// Data classified as restricted is never available to AI agents, whatever the user may do in Jira. forbid overrides every
// permit, so no group or scope can open it.

@id("jira-forbid-restricted-project")
forbid (principal, action, resource is Jira::Project)
when { resource.category == "restricted" };

@id("jira-forbid-restricted-issue")
forbid (principal, action, resource is Jira::Issue)
when { resource.project.category == "restricted" };
