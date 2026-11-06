// Reading follows the user's own rights in Jira; the server adds the group check and the classification rule.

@id("jira-read")
permit (
  principal in UserGroup::"mcp-jira-read",
  action == Jira::Action::"read",
  resource
)
when { resource is Jira::Project || resource is Jira::Issue };
