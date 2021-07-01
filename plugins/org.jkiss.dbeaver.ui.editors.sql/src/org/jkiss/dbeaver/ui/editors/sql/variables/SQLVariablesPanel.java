/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2021 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.ui.editors.sql.variables;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.PatternFilter;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCScriptContext;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.registry.SQLQueryParameterRegistry;
import org.jkiss.dbeaver.ui.*;
import org.jkiss.dbeaver.ui.controls.ListContentProvider;
import org.jkiss.dbeaver.ui.controls.ProgressPageControl;
import org.jkiss.dbeaver.ui.editors.StringEditorInput;
import org.jkiss.dbeaver.ui.editors.SubEditorSite;
import org.jkiss.dbeaver.ui.editors.TextEditorUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * SQLVariablesPanel
 */
public class SQLVariablesPanel extends Composite {

    static protected final Log log = Log.getLog(SQLVariablesPanel.class);

    private final SQLEditor mainEditor;
    private SQLEditorBase valueEditor;
    private TableViewer varsTable;
    private boolean showParameters;

    public SQLVariablesPanel(Composite parent, SQLEditor editor)
    {
        super(parent, SWT.NONE);
        this.mainEditor = editor;

        setLayout(new FillLayout());
    }

    private void createControls() {
        SashForm sash = new SashForm(this, SWT.VERTICAL);

        // Variables table
        {
            VariableListControl variableListControl = new VariableListControl(sash);
            variableListControl.createOrSubstituteProgressPanel(mainEditor.getSite());
        }

        // Editor
        {
            Composite editorGroup = UIUtils.createPlaceholder(sash, 1);

            UIUtils.createControlLabel(editorGroup, "Value");

            Composite editorPH = new Composite(editorGroup, SWT.NONE);
            GridData gd = new GridData(GridData.FILL_BOTH);
            gd.verticalIndent = 3;
            gd.horizontalSpan = 1;
            gd.minimumHeight = 100;
            gd.minimumWidth = 100;
            editorPH.setLayoutData(gd);
            editorPH.setLayout(new FillLayout());

            valueEditor = new SQLEditorBase() {
                @Nullable
                @Override
                public DBCExecutionContext getExecutionContext() {
                    return mainEditor.getExecutionContext();
                }

                @Override
                public void createPartControl(Composite parent) {
                    super.createPartControl(parent);
                    getAction(ITextEditorActionConstants.CONTEXT_PREFERENCES).setEnabled(false);
                }

                @Override
                public boolean isFoldingEnabled() {
                    return false;
                }
            };
            try {
                valueEditor.init(new SubEditorSite(mainEditor.getSite()),
                    new StringEditorInput("Variable value", "", true, GeneralUtils.getDefaultFileEncoding()));
            } catch (PartInitException e) {
                log.error(e);
            }
            valueEditor.createPartControl(editorPH);
            valueEditor.reloadSyntaxRules();

            //valueEditor.getEditorControl().setEnabled(false);

            valueEditor.getEditorControlWrapper().setLayoutData(new GridData(GridData.FILL_BOTH));
        }
    }

    private void editCurrentVariable() {
        ISelection selection = varsTable.getSelection();
        StyledText editorControl = valueEditor.getEditorControl();
        if (editorControl == null) {
            return;
        }
        if (!selection.isEmpty()) {
            //TableItem item = varsTable.getItem(selectionIndex);
            DBCScriptContext.VariableInfo variable = (DBCScriptContext.VariableInfo) ((IStructuredSelection)selection).getFirstElement();

            StringEditorInput sqlInput = new StringEditorInput(
                "Variable " + variable.name,
                CommonUtils.toString(variable.value),
                false,
                GeneralUtils.DEFAULT_ENCODING
                );
            valueEditor.setInput(sqlInput);
            valueEditor.reloadSyntaxRules();
        }
    }

    public void refreshVariables() {
        if (varsTable == null) {
            createControls();
        }

        SQLScriptContext context = mainEditor.getGlobalScriptContext();

        //varsTable.removeAll();
        List<DBCScriptContext.VariableInfo> variables = context.getVariables();
        if (showParameters) {
            for (SQLQueryParameterRegistry.ParameterInfo param : SQLQueryParameterRegistry.getInstance().getAllParameters()) {
                if (context.hasVariable(param.name)) {
                    continue;
                }
                Object parameterValue = context.getParameterDefaultValue(param.name);
                if (parameterValue == null) {
                    parameterValue = param.value;
                }
                variables.add(new DBCScriptContext.VariableInfo(
                    param.name,
                    parameterValue,
                    DBCScriptContext.VariableType.PARAMETER));
            }
        }

        varsTable.setInput(variables);
        UIUtils.packColumns(varsTable.getTable(), true);

        valueEditor.setInput(new StringEditorInput(
            "Variable",
            "",
            true,
            GeneralUtils.DEFAULT_ENCODING
        ));
        valueEditor.reloadSyntaxRules();
    }

    private class VariableListControl extends ProgressPageControl {

        private final ISearchExecutor searcher;
        private Action addAction;
        private Action deleteAction;

        public VariableListControl(Composite parent) {
            super(parent, SWT.SHEET);
            searcher = new ISearchExecutor() {
                @Override
                public boolean performSearch(String searchString, int options) {
                    try {
                        PatternFilter searchFilter = new PatternFilter() {
                            protected boolean isLeafMatch(Viewer viewer, Object element) {
                                DBCScriptContext.VariableInfo variable = (DBCScriptContext.VariableInfo) element;

                                return wordMatches(variable.name) || wordMatches(CommonUtils.toString(variable.value));
                            }
                        };
                        searchFilter.setPattern(searchString);
                            //(options & SEARCH_CASE_SENSITIVE) != 0);
                        varsTable.setFilters(new ViewerFilter[]{ searchFilter });
                        return true;
                    } catch (PatternSyntaxException e) {
                        log.error(e.getMessage());
                        return false;
                    }
                }

                @Override
                public void cancelSearch() {
                    varsTable.setFilters(new ViewerFilter[0]);
                }
            };

            varsTable = new TableViewer(this, SWT.SINGLE | SWT.FULL_SELECTION);
            varsTable.getTable().setHeaderVisible(true);
            varsTable.getTable().setLinesVisible(true);
            varsTable.getTable().setLayoutData(new GridData(GridData.FILL_BOTH));

            UIUtils.createTableColumn(varsTable.getTable(), SWT.LEFT, "Variable");
            UIUtils.createTableColumn(varsTable.getTable(), SWT.LEFT, "Value");
            UIUtils.createTableColumn(varsTable.getTable(), SWT.LEFT, "Type");

            varsTable.setContentProvider(new ListContentProvider());
            varsTable.setLabelProvider(new CellLabelProvider() {
                @Override
                public void update(ViewerCell cell) {
                    DBCScriptContext.VariableInfo variable = (DBCScriptContext.VariableInfo) cell.getElement();
                    switch (cell.getColumnIndex()) {
                        case 0:
                            cell.setText(variable.name);
                            break;
                        case 1:
                            cell.setText(CommonUtils.toString(variable.value));
                            break;
                        case 2:
                            cell.setText(variable.type.getTitle());
                            break;
                    }
                }
            });

            varsTable.addSelectionChangedListener(event -> {
                if (deleteAction != null) {
                    deleteAction.setEnabled(!event.getSelection().isEmpty());
                    updateActions();
                }
                editCurrentVariable();
            });
        }

        @Override
        protected ISearchExecutor getSearchRunner() {
            return searcher;
        }

        protected void addSearchAction(IContributionManager contributionManager) {
            contributionManager.add(new Action("Find variable", DBeaverIcons.getImageDescriptor(UIIcon.SEARCH)) {
                @Override
                public void run() {
                    performSearch(SearchType.NONE);
                }
            });
        }

        @Override
        protected void createSearchControls() {
            super.createSearchControls();
            Text textControl = getSearchTextControl();
            if (textControl != null) {
                TextEditorUtils.enableHostEditorKeyBindingsSupport(mainEditor.getSite(), textControl);
            }
        }

        @Override
        public void fillCustomActions(IContributionManager contributionManager) {
            super.fillCustomActions(contributionManager);

            addAction = new Action("Add variable", DBeaverIcons.getImageDescriptor(UIIcon.ADD)) {
                @Override
                public void run() {
                    super.run();
                }
            };
            contributionManager.add(addAction);
            deleteAction = new Action("Delete variable", DBeaverIcons.getImageDescriptor(UIIcon.DELETE)) {
                @Override
                public void run() {
                    super.run();
                }
            };
            deleteAction.setEnabled(false);
            contributionManager.add(deleteAction);

            Action showParamsAction = new Action("Show parameters", Action.AS_CHECK_BOX) {
                @Override
                public void run() {
                    showParameters = !showParameters;
                    refreshVariables();
                }
            };
            showParamsAction.setChecked(showParameters);
            showParamsAction.setImageDescriptor(DBeaverIcons.getImageDescriptor(UIIcon.SQL_VARIABLES));
            showParamsAction.setDescription("Show query parameters");
            contributionManager.add(ActionUtils.makeActionContribution(showParamsAction, true));
        }
    }
}