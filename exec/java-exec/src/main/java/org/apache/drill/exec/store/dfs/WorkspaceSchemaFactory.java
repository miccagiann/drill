/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.dfs;

import static com.google.common.collect.Collections2.transform;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.unmodifiableList;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.FunctionParameter;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TableMacro;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.drill.common.config.LogicalPlanPersistence;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.logical.FormatPluginConfig;
import org.apache.drill.common.scanner.persistence.ScanResult;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.dotdrill.DotDrillFile;
import org.apache.drill.exec.dotdrill.DotDrillType;
import org.apache.drill.exec.dotdrill.DotDrillUtil;
import org.apache.drill.exec.dotdrill.View;
import org.apache.drill.exec.planner.logical.CreateTableEntry;
import org.apache.drill.exec.planner.logical.DrillTable;
import org.apache.drill.exec.planner.logical.DrillTranslatableTable;
import org.apache.drill.exec.planner.logical.DrillViewTable;
import org.apache.drill.exec.planner.logical.DynamicDrillTable;
import org.apache.drill.exec.planner.logical.FileSystemCreateTableEntry;
import org.apache.drill.exec.planner.sql.DrillOperatorTable;
import org.apache.drill.exec.planner.sql.ExpandingConcurrentMap;
import org.apache.drill.exec.store.AbstractSchema;
import org.apache.drill.exec.store.PartitionNotFoundException;
import org.apache.drill.exec.store.SchemaConfig;
import org.apache.drill.exec.util.ImpersonationUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.AccessControlException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class WorkspaceSchemaFactory {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(WorkspaceSchemaFactory.class);

  private final List<FormatMatcher> fileMatchers;
  private final List<FormatMatcher> dropFileMatchers;
  private final List<FormatMatcher> dirMatchers;

  private final WorkspaceConfig config;
  private final Configuration fsConf;
  private final String storageEngineName;
  private final String schemaName;
  private final FileSystemPlugin plugin;
  private final ObjectMapper mapper;
  private final LogicalPlanPersistence logicalPlanPersistence;
  private final Path wsPath;

  private final FormatPluginOptionExtractor optionExtractor;

  public WorkspaceSchemaFactory(
      FileSystemPlugin plugin,
      String schemaName,
      String storageEngineName,
      WorkspaceConfig config,
      List<FormatMatcher> formatMatchers,
      LogicalPlanPersistence logicalPlanPersistence,
      ScanResult scanResult) throws ExecutionSetupException, IOException {
    this.logicalPlanPersistence = logicalPlanPersistence;
    this.fsConf = plugin.getFsConf();
    this.plugin = plugin;
    this.config = config;
    this.mapper = logicalPlanPersistence.getMapper();
    this.fileMatchers = Lists.newArrayList();
    this.dirMatchers = Lists.newArrayList();
    this.storageEngineName = storageEngineName;
    this.schemaName = schemaName;
    this.wsPath = new Path(config.getLocation());
    this.optionExtractor = new FormatPluginOptionExtractor(scanResult);

    for (FormatMatcher m : formatMatchers) {
      if (m.supportDirectoryReads()) {
        dirMatchers.add(m);
      }
      fileMatchers.add(m);
    }

    // NOTE: Add fallback format matcher if given in the configuration. Make sure fileMatchers is an order-preserving list.
    final String defaultInputFormat = config.getDefaultInputFormat();
    if (!Strings.isNullOrEmpty(defaultInputFormat)) {
      final FormatPlugin formatPlugin = plugin.getFormatPlugin(defaultInputFormat);
      if (formatPlugin == null) {
        final String message = String.format("Unable to find default input format[%s] for workspace[%s.%s]",
            defaultInputFormat, storageEngineName, schemaName);
        throw new ExecutionSetupException(message);
      }
      final FormatMatcher fallbackMatcher = new BasicFormatMatcher(formatPlugin,
          ImmutableList.of(Pattern.compile(".*")), ImmutableList.<MagicString>of());
      fileMatchers.add(fallbackMatcher);
      dropFileMatchers = fileMatchers.subList(0, fileMatchers.size() - 1);
    } else {
      dropFileMatchers = fileMatchers.subList(0, fileMatchers.size());
    }
  }

  /**
   * Checks whether the given user has permission to list files/directories under the workspace directory.
   *
   * @param userName User who is trying to access the workspace.
   * @return True if the user has access. False otherwise.
   */
  public boolean accessible(final String userName) throws IOException {
    final FileSystem fs = ImpersonationUtil.createFileSystem(userName, fsConf);
    try {
      // We have to rely on the listStatus as a FileSystem can have complicated controls such as regular unix style
      // permissions, Access Control Lists (ACLs) or Access Control Expressions (ACE). Hadoop 2.7 version of FileSystem
      // has a limited private API (FileSystem.access) to check the permissions directly
      // (see https://issues.apache.org/jira/browse/HDFS-6570). Drill currently relies on Hadoop 2.5.0 version of
      // FileClient. TODO: Update this when DRILL-3749 is fixed.
      fs.listStatus(wsPath);
    } catch (final UnsupportedOperationException e) {
      logger.trace("The filesystem for this workspace does not support this operation.", e);
    } catch (final FileNotFoundException | AccessControlException e) {
      return false;
    }

    return true;
  }

  private Path getViewPath(String name) {
    return DotDrillType.VIEW.getPath(config.getLocation(), name);
  }

  public WorkspaceSchema createSchema(List<String> parentSchemaPath, SchemaConfig schemaConfig) throws IOException {
    return new WorkspaceSchema(parentSchemaPath, schemaName, schemaConfig);
  }

  /**
   * Implementation of a table macro that generates a table based on parameters
   */
  static final class WithOptionsTableMacro implements TableMacro {

    private final TableSignature sig;
    private final WorkspaceSchema schema;

    WithOptionsTableMacro(TableSignature sig, WorkspaceSchema schema) {
      super();
      this.sig = sig;
      this.schema = schema;
    }

    @Override
    public List<FunctionParameter> getParameters() {
      List<FunctionParameter> result = new ArrayList<>();
      for (int i = 0; i < sig.params.size(); i++) {
        final TableParamDef p = sig.params.get(i);
        final int ordinal = i;
        result.add(new FunctionParameter() {
          @Override
          public int getOrdinal() {
            return ordinal;
          }

          @Override
          public String getName() {
            return p.name;
          }

          @Override
          public RelDataType getType(RelDataTypeFactory typeFactory) {
            return typeFactory.createJavaType(p.type);
          }

          @Override
          public boolean isOptional() {
            return p.optional;
          }
        });
      }
      return result;
    }

    @Override
    public TranslatableTable apply(List<Object> arguments) {
      return new DrillTranslatableTable(schema.getDrillTable(new TableInstance(sig, arguments)));
    }

  }

  private static Object[] array(Object... objects) {
    return objects;
  }

  static final class TableInstance {
    final TableSignature sig;
    final List<Object> params;

    TableInstance(TableSignature sig, List<Object> params) {
      super();
      if (params.size() != sig.params.size()) {
        throw UserException.parseError()
            .message(
                "should have as many params (%d) as signature (%d)",
                params.size(), sig.params.size())
            .addContext("table", sig.name)
            .build(logger);
      }
      this.sig = sig;
      this.params = unmodifiableList(params);
    }

    String presentParams() {
      StringBuilder sb = new StringBuilder("(");
      boolean first = true;
      for (int i = 0; i < params.size(); i++) {
        Object param = params.get(i);
        if (param != null) {
          if (first) {
            first = false;
          } else {
            sb.append(", ");
          }
          TableParamDef paramDef = sig.params.get(i);
          sb.append(paramDef.name).append(": ").append(paramDef.type.getSimpleName()).append(" => ").append(param);
        }
      }
      sb.append(")");
      return sb.toString();
    }

    private Object[] toArray() {
      return array(sig, params);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(toArray());
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof TableInstance) {
        return Arrays.equals(this.toArray(), ((TableInstance)obj).toArray());
      }
      return false;
    }

    @Override
    public String toString() {
      return sig.name + (params.size() == 0 ? "" : presentParams());
    }
  }

  static final class TableParamDef {
    final String name;
    final Class<?> type;
    final boolean optional;

    TableParamDef(String name, Class<?> type) {
      this(name, type, false);
    }

    TableParamDef(String name, Class<?> type, boolean optional) {
      this.name = name;
      this.type = type;
      this.optional = optional;
    }

    TableParamDef optional() {
      return new TableParamDef(name, type, true);
    }

    private Object[] toArray() {
      return array(name, type, optional);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(toArray());
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof TableParamDef) {
        return Arrays.equals(this.toArray(), ((TableParamDef)obj).toArray());
      }
      return false;
    }

    @Override
    public String toString() {
      String p = name + ": " + type;
      return optional ? "[" + p + "]" : p;
    }
  }

  static final class TableSignature {
    final String name;
    final List<TableParamDef> params;

    TableSignature(String name, TableParamDef... params) {
      this(name, Arrays.asList(params));
    }

    TableSignature(String name, List<TableParamDef> params) {
      this.name = name;
      this.params = unmodifiableList(params);
    }

    private Object[] toArray() {
      return array(name, params);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(toArray());
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof TableSignature) {
        return Arrays.equals(this.toArray(), ((TableSignature)obj).toArray());
      }
      return false;
    }

    @Override
    public String toString() {
      return name + params;
    }
  }

  public class WorkspaceSchema extends AbstractSchema implements ExpandingConcurrentMap.MapValueFactory<TableInstance, DrillTable> {
    private final ExpandingConcurrentMap<TableInstance, DrillTable> tables = new ExpandingConcurrentMap<>(this);
    private final SchemaConfig schemaConfig;
    private final DrillFileSystem fs;

    public WorkspaceSchema(List<String> parentSchemaPath, String wsName, SchemaConfig schemaConfig) throws IOException {
      super(parentSchemaPath, wsName);
      this.schemaConfig = schemaConfig;
      this.fs = ImpersonationUtil.createFileSystem(schemaConfig.getUserName(), fsConf);
    }

    DrillTable getDrillTable(TableInstance key) {
      return tables.get(key);
    }

    @Override
    public boolean createView(View view) throws IOException {
      Path viewPath = getViewPath(view.getName());
      boolean replaced = fs.exists(viewPath);
      final FsPermission viewPerms =
          new FsPermission(schemaConfig.getOption(ExecConstants.NEW_VIEW_DEFAULT_PERMS_KEY).string_val);
      try (OutputStream stream = DrillFileSystem.create(fs, viewPath, viewPerms)) {
        mapper.writeValue(stream, view);
      }
      return replaced;
    }

    @Override
    public Iterable<String> getSubPartitions(String table,
                                             List<String> partitionColumns,
                                             List<String> partitionValues
    ) throws PartitionNotFoundException {

      List<FileStatus> fileStatuses;
      try {
        fileStatuses = getFS().list(false, new Path(getDefaultLocation(), table));
      } catch (IOException e) {
        throw new PartitionNotFoundException("Error finding partitions for table " + table, e);
      }
      return new SubDirectoryList(fileStatuses);
    }

    @Override
    public void dropView(String viewName) throws IOException {
      fs.delete(getViewPath(viewName), false);
    }

    private Set<String> getViews() {
      Set<String> viewSet = Sets.newHashSet();
      // Look for files with ".view.drill" extension.
      List<DotDrillFile> files;
      try {
        files = DotDrillUtil.getDotDrills(fs, new Path(config.getLocation()), DotDrillType.VIEW);
        for (DotDrillFile f : files) {
          viewSet.add(f.getBaseName());
        }
      } catch (UnsupportedOperationException e) {
        logger.debug("The filesystem for this workspace does not support this operation.", e);
      } catch (AccessControlException e) {
        if (!schemaConfig.getIgnoreAuthErrors()) {
          logger.debug(e.getMessage());
          throw UserException
              .permissionError(e)
              .message("Not authorized to list view tables in schema [%s]", getFullSchemaName())
              .build(logger);
        }
      } catch (Exception e) {
        logger.warn("Failure while trying to list .view.drill files in workspace [{}]", getFullSchemaName(), e);
      }

      return viewSet;
    }

    private Set<String> rawTableNames() {
      return newHashSet(
          transform(tables.keySet(), new com.google.common.base.Function<TableInstance, String>() {
        @Override
        public String apply(TableInstance input) {
          return input.sig.name;
        }
      }));
    }

    @Override
    public Set<String> getTableNames() {
      return Sets.union(rawTableNames(), getViews());
    }

    @Override
    public Set<String> getFunctionNames() {
      return rawTableNames();
    }

    @Override
    public List<Function> getFunctions(String name) {
      List<TableSignature> sigs = optionExtractor.getTableSignatures(name);
      return Lists.transform(sigs, new com.google.common.base.Function<TableSignature, Function>() {
        @Override
        public Function apply(TableSignature input) {
          return new WithOptionsTableMacro(input, WorkspaceSchema.this);
        }
      });
    }

    private View getView(DotDrillFile f) throws IOException {
      assert f.getType() == DotDrillType.VIEW;
      return f.getView(logicalPlanPersistence);
    }

    @Override
    public Table getTable(String tableName) {
      TableInstance tableKey = new TableInstance(new TableSignature(tableName), ImmutableList.of());
      // first check existing tables.
      if (tables.alreadyContainsKey(tableKey)) {
        return tables.get(tableKey);
      }

      // then look for files that start with this name and end in .drill.
      List<DotDrillFile> files = Collections.emptyList();
      try {
        try {
          files = DotDrillUtil.getDotDrills(fs, new Path(config.getLocation()), tableName, DotDrillType.VIEW);
        } catch (AccessControlException e) {
          if (!schemaConfig.getIgnoreAuthErrors()) {
            logger.debug(e.getMessage());
            throw UserException.permissionError(e)
              .message("Not authorized to list or query tables in schema [%s]", getFullSchemaName())
              .build(logger);
          }
        } catch (IOException e) {
          logger.warn("Failure while trying to list view tables in workspace [{}]", tableName, getFullSchemaName(), e);
        }

        for (DotDrillFile f : files) {
          switch (f.getType()) {
          case VIEW:
            try {
              return new DrillViewTable(getView(f), f.getOwner(), schemaConfig.getViewExpansionContext());
            } catch (AccessControlException e) {
              if (!schemaConfig.getIgnoreAuthErrors()) {
                logger.debug(e.getMessage());
                throw UserException.permissionError(e)
                  .message("Not authorized to read view [%s] in schema [%s]", tableName, getFullSchemaName())
                  .build(logger);
              }
            } catch (IOException e) {
              logger.warn("Failure while trying to load {}.view.drill file in workspace [{}]", tableName, getFullSchemaName(), e);
            }
          }
        }
      } catch (UnsupportedOperationException e) {
        logger.debug("The filesystem for this workspace does not support this operation.", e);
      }

      return tables.get(tableKey);
    }

    @Override
    public boolean isMutable() {
      return config.isWritable();
    }

    public DrillFileSystem getFS() {
      return fs;
    }

    public String getDefaultLocation() {
      return config.getLocation();
    }

    @Override
    public CreateTableEntry createNewTable(String tableName, List<String> partitonColumns) {
      String storage = schemaConfig.getOption(ExecConstants.OUTPUT_FORMAT_OPTION).string_val;
      FormatPlugin formatPlugin = plugin.getFormatPlugin(storage);
      if (formatPlugin == null) {
        throw new UnsupportedOperationException(
          String.format("Unsupported format '%s' in workspace '%s'", config.getDefaultInputFormat(),
              Joiner.on(".").join(getSchemaPath())));
      }

      return new FileSystemCreateTableEntry(
          (FileSystemConfig) plugin.getConfig(),
          formatPlugin,
          config.getLocation() + Path.SEPARATOR + tableName,
          partitonColumns);
    }

    @Override
    public String getTypeName() {
      return FileSystemConfig.NAME;
    }

    private DrillTable isReadable(FormatMatcher m, FileSelection fileSelection) throws IOException {
      return m.isReadable(fs, fileSelection, plugin, storageEngineName, schemaConfig.getUserName());
    }

    @Override
    public DrillTable create(TableInstance key) {
      try {
        final FileSelection fileSelection = FileSelection.create(fs, config.getLocation(), key.sig.name);
        if (fileSelection == null) {
          return null;
        }

        final boolean hasDirectories = fileSelection.containsDirectories(fs);
        if (key.sig.params.size() > 0) {
          FormatPluginConfig fconfig = optionExtractor.createConfigForTable(key);
          return new DynamicDrillTable(
              plugin, storageEngineName, schemaConfig.getUserName(),
              new FormatSelection(fconfig, fileSelection));
        }
        if (hasDirectories) {
          for (final FormatMatcher matcher : dirMatchers) {
            try {
              DrillTable table = matcher.isReadable(fs, fileSelection, plugin, storageEngineName, schemaConfig.getUserName());
              if (table != null) {
                return table;
              }
            } catch (IOException e) {
              logger.debug("File read failed.", e);
            }
          }
        }

        final FileSelection newSelection = hasDirectories ? fileSelection.minusDirectories(fs) : fileSelection;
        if (newSelection == null) {
          return null;
        }

        for (final FormatMatcher matcher : fileMatchers) {
          DrillTable table = matcher.isReadable(fs, newSelection, plugin, storageEngineName, schemaConfig.getUserName());
          if (table != null) {
            return table;
          }
        }
        return null;

      } catch (AccessControlException e) {
        if (!schemaConfig.getIgnoreAuthErrors()) {
          logger.debug(e.getMessage());
          throw UserException.permissionError(e)
            .message("Not authorized to read table [%s] in schema [%s]", key, getFullSchemaName())
            .build(logger);
        }
      } catch (IOException e) {
        logger.debug("Failed to create DrillTable with root {} and name {}", config.getLocation(), key, e);
      }

      return null;
    }

    private FormatMatcher findMatcher(FileStatus file) {
      FormatMatcher matcher = null;
      try {
        for (FormatMatcher m : dropFileMatchers) {
          if (m.isFileReadable(fs, file)) {
            return m;
          }
        }
      } catch (IOException e) {
        logger.debug("Failed to find format matcher for file: %s", file, e);
      }
      return matcher;
    }

    @Override
    public void destroy(DrillTable value) {
    }

    /**
     * Check if the table contains homogenenous files that can be read by Drill. Eg: parquet, json csv etc.
     * However if it contains more than one of these formats or a totally different file format that Drill cannot
     * understand then we will raise an exception.
     * @param tableName - name of the table to be checked for homogeneous property
     * @return
     * @throws IOException
     */
    private boolean isHomogeneous(String tableName) throws IOException {
      FileSelection fileSelection = FileSelection.create(fs, config.getLocation(), tableName);

      if (fileSelection == null) {
        throw UserException
            .validationError()
            .message(String.format("Table [%s] not found", tableName))
            .build(logger);
      }

      FormatMatcher matcher = null;
      Queue<FileStatus> listOfFiles = new LinkedList<>();
      listOfFiles.addAll(fileSelection.getStatuses(fs));

      while (!listOfFiles.isEmpty()) {
        FileStatus currentFile = listOfFiles.poll();
        if (currentFile.isDirectory()) {
          listOfFiles.addAll(fs.list(true, currentFile.getPath()));
        } else {
          if (matcher != null) {
            if (!matcher.isFileReadable(fs, currentFile)) {
              return false;
            }
          } else {
            matcher = findMatcher(currentFile);
            // Did not match any of the file patterns, exit
            if (matcher == null) {
              return false;
            }
          }
        }
      }
      return true;
    }

    /**
     * We check if the table contains homogeneous file formats that Drill can read. Once the checks are performed
     * we rename the file to start with an "_". After the rename we issue a recursive delete of the directory.
     * @param table - Path of table to be dropped
     */
    @Override
    public void dropTable(String table) {
      DrillFileSystem fs = getFS();
      String defaultLocation = getDefaultLocation();
      try {
        if (!isHomogeneous(table)) {
          throw UserException
              .validationError()
              .message("Table contains different file formats. \n" +
                  "Drop Table is only supported for directories that contain homogeneous file formats consumable by Drill")
              .build(logger);
        }

        StringBuilder tableRenameBuilder = new StringBuilder();
        int lastSlashIndex = table.lastIndexOf(Path.SEPARATOR);
        if (lastSlashIndex != -1) {
          tableRenameBuilder.append(table.substring(0, lastSlashIndex + 1));
        }
        // Generate unique identifier which will be added as a suffix to the table name
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long time =  (System.currentTimeMillis()/1000);
        Long p1 = ((Integer.MAX_VALUE - time) << 32) + r.nextInt();
        Long p2 = r.nextLong();
        final String fileNameDelimiter = DrillFileSystem.HIDDEN_FILE_PREFIX;
        String[] pathSplit = table.split(Path.SEPARATOR);
        /*
         * Builds the string for the renamed table
         * Prefixes the table name with an underscore (intent for this to be treated as a hidden file)
         * and suffixes the table name with unique identifiers (similar to how we generate query id's)
         * separated by underscores
         */
        tableRenameBuilder
            .append(DrillFileSystem.HIDDEN_FILE_PREFIX)
            .append(pathSplit[pathSplit.length - 1])
            .append(fileNameDelimiter)
            .append(p1.toString())
            .append(fileNameDelimiter)
            .append(p2.toString());

        String tableRename = tableRenameBuilder.toString();
        fs.rename(new Path(defaultLocation, table), new Path(defaultLocation, tableRename));
        fs.delete(new Path(defaultLocation, tableRename), true);
      } catch (AccessControlException e) {
        throw UserException
            .permissionError()
            .message("Unauthorized to drop table", e)
            .build(logger);
      } catch (IOException e) {
        throw UserException
            .dataWriteError()
            .message("Failed to drop table", e)
            .build(logger);
      }
    }
  }
}
