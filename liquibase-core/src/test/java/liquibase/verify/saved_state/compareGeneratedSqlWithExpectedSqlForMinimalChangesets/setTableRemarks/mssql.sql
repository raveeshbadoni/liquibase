-- Database: mssql
-- Change Parameter: remarks=A String
-- Change Parameter: tableName=person
DECLARE @TableName SYSNAME set @TableName = N'person'; DECLARE @FullTableName SYSNAME; SET @FullTableName = N'dbo.person';DECLARE @MS_DescriptionValue NVARCHAR(3749); SET @MS_DescriptionValue = N'A String';DECLARE @MS_Description NVARCHAR(3749) set @MS_Description = NULL; SET @MS_Description = (SELECT CAST(Value AS NVARCHAR(3749)) AS [MS_Description] FROM sys.extended_properties AS ep WHERE ep.major_id = OBJECT_ID(@FullTableName) AND ep.name = N'MS_Description' AND ep.minor_id=0); IF @MS_Description IS NULL BEGIN EXEC sys.sp_addextendedproperty @name  = N'MS_Description', @value = @MS_DescriptionValue, @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = @TableName; END ELSE BEGIN EXEC sys.sp_updateextendedproperty @name  = N'MS_Description', @value = @MS_DescriptionValue, @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = @TableName; END;