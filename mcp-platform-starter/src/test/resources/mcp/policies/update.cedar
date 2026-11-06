// Changes only inside the projects granted by the scope mapping. `principal in resource.scopes` is true when one of the
// user's groups is mapped to the resource scope; an issue carries the scope of its project.

@id("jira-update-project-in-scope")
permit (
  principal in UserGroup::"mcp-jira-write",
  action == Jira::Action::"update",
  resource is Jira::Project
)
when { principal in resource.scopes };

@id("jira-update-issue-in-scope")
permit (
  principal in UserGroup::"mcp-jira-write",
  action == Jira::Action::"update",
  resource is Jira::Issue
)
when { principal in resource.scopes };

@id("jira-delete-issue-in-scope")
permit (
  principal in UserGroup::"mcp-jira-destructive",
  action == Jira::Action::"delete",
  resource is Jira::Issue
)
when { principal in resource.scopes };
