import * as React from "react";
import styled from "styled-components";
import {Box, Button, Flex, Icon, Input, Label, Text, Tooltip as UITooltip} from "@/ui-components";
import {getCssVar} from "@/Utilities/StyledComponentsUtilities";
import {
    Cell as SheetCell,
    DropdownCell,
    FuzzyCell, Sheet, SheetCallbacks,
    SheetDropdownOption,
    SheetRenderer, StaticCell,
    TextCell
} from "@/ui-components/Sheet";
import {
    deposit,
    DepositToWalletRequestItem,
    explainAllocation, normalizeBalanceForBackend,
    normalizeBalanceForFrontend, ProductType,
    productTypes,
    productTypeToIcon,
    productTypeToTitle, retrieveRecipient,
    TransferRecipient, updateAllocation, UpdateAllocationRequestItem,
    Wallet
} from "@/Accounting";
import {APICallState, callAPI} from "@/Authentication/DataHook";
import {PageV2} from "@/UCloud";
import {MutableRefObject, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from "react";
import {timestampUnixMs} from "@/UtilityFunctions";
import {bulkRequestOf} from "@/DefaultObjects";
import HighlightedCard from "@/ui-components/HighlightedCard";
import {ConfirmCancelButtons} from "@/UtilityComponents";
import {Operation} from "@/ui-components/Operation";
import {SubAllocation} from "@/Project/index";

const Circle = styled(Box)`
  border-radius: 500px;
  width: 20px;
  height: 20px;
  border: 1px solid ${getCssVar("black")};
  margin: 4px 4px 4px 8px;
  cursor: pointer;
`;

function formatTimestampForInput(timestamp: number): string {
    const d = new Date(timestamp);
    let res = "";
    res += d.getUTCFullYear().toString().padStart(4, '0');
    res += "-";
    res += (d.getMonth() + 1).toString().padStart(2, '0');
    res += "-";
    res += (d.getDate()).toString().padStart(2, '0');
    return res;
}

const subAllocationsDirtyKey = "subAllocationsDirty";
const unsavedPrefix = "unsaved";

type RowOrDeleted = "deleted" | ((string | null)[]);

function writeRow(s: SheetRenderer, row: RowOrDeleted, rowNumber: number) {
    if (row !== "deleted") {
        let col = 0;
        for (const value of row) {
            s.writeValue(col++, rowNumber, value ?? undefined);
        }
    }
}

function titleForSubAllocation(alloc: SubAllocation): string {
    return rawAllocationTitleInRow(alloc.productCategoryId.name, alloc.productCategoryId.provider);
}

function titleForWallet(wallet: Wallet): string {
    return rawAllocationTitleInRow(wallet.paysFor.name, wallet.paysFor.provider);
}

function rawAllocationTitleInRow(category: string, provider: string) {
    return `${category} @ ${provider}`;
}

function writeAllocations(
    s: SheetRenderer,
    allocations: SubAllocation[],
    dirtyRowStorage: Record<string, RowOrDeleted>,
    sessionId: number,
    unsavedRowIds: { current: number }
) {
    let row = 0;
    s.clear();

    for (const key of Object.keys(dirtyRowStorage)) {
        s.addRow(key);
        const draftCopy = dirtyRowStorage[key];
        writeRow(s, draftCopy, row);
        s.writeValue(colStatus, row, undefined);
        row++;
    }

    allocations.forEach(alloc => {
        const draftCopy = dirtyRowStorage[alloc.id];
        if (draftCopy !== "deleted") {
            if (draftCopy) return;
            s.addRow(alloc.id);
            alloc.path

            s.writeValue(colRecipientType, row, alloc.workspaceIsProject ? "PROJECT" : "USER");
            s.writeValue(colRecipient, row, alloc.workspaceTitle);
            s.writeValue(colProductType, row, alloc.productType);
            s.writeValue(colProduct, row, titleForSubAllocation(alloc));
            {
                let path = alloc.path.split(".");
                s.writeValue(colAllocation, row, path[path.length - 2]);
            }
            s.writeValue(colStartDate, row, formatTimestampForInput(alloc.startDate));
            if (alloc.endDate) s.writeValue(colEndDate, row, formatTimestampForInput(alloc.endDate));
            s.writeValue(colAmount, row, normalizeBalanceForFrontend(alloc.remaining, alloc.productType,
                alloc.chargeType, alloc.unit, false, 0).replace('.', '').toString());
            s.writeValue(colStatus, row, undefined);
            s.writeValue(colUnit, row, undefined);

            row++;
        }
    });

    s.addRow(`${unsavedPrefix}-${sessionId}-${unsavedRowIds.current++}`);
    s.writeValue(colRecipientType, row, "PROJECT");
    s.writeValue(colProductType, row, "STORAGE");
    s.writeValue(colStatus, row, undefined);
}

function parseDateFromInput(value: string): number | null {
    const splitValue = value.split("-");
    if (splitValue.length != 3) return null;
    const year = parseInt(splitValue[0], 10);
    const month = parseInt(splitValue[1], 10);
    const date = parseInt(splitValue[2], 10);

    // NOTE(Dan): Time-travel to the past is not supported by this application.
    if (year < 2020) return null;
    // NOTE(Dan): I am assuming that someone will rewrite this code before this date is relevant.
    if (year >= 2100) return null;

    if (month < 1 || month > 12) return null;

    // NOTE(Dan): Only doing some basic validation. The browser should not allow these values. The backend will reject
    // invalid values also.
    if (date < 1 || date > 31) return null;

    return Math.floor(new Date(year, month - 1, date).getTime());
}

const colRecipientType: 0 = 0 as const;
const colRecipient: 1 = 1 as const;
const colProductType: 2 = 2 as const;
const colProduct: 3 = 3 as const;
const colAllocation: 4 = 4 as const;
const colStartDate: 5 = 5 as const;
const colEndDate: 6 = 6 as const;
const colAmount: 7 = 7 as const;
const colUnit: 8 = 8 as const;
const colStatus: 9 = 9 as const;

export const SubAllocationViewer: React.FunctionComponent<{
    wallets: APICallState<PageV2<Wallet>>;
    allocations: APICallState<PageV2<SubAllocation>>;
    generation: number;
    loadMore: () => void;
    onQuery: (query: string) => void;
    filterByAllocation: (allocationId: string) => void;
    filterByWorkspace: (workspaceId: string, isProject: boolean) => void;
}> = ({allocations, loadMore, generation, filterByAllocation, filterByWorkspace, wallets, onQuery}) => {
    const originalRows = useRef<Record<string, SubAllocation>>({});
    const projectLookupTable = useRef<Record<string, { rows: SubAllocation[]; projectId: string }[]>>({});
    const sessionId = useMemo(() => Math.ceil(Math.random() * 1000000000), []);
    const unsavedRowIds = useRef(0);
    const dirtyRowStorageRef: MutableRefObject<Record<string, RowOrDeleted>> = useRef({});
    const [dirtyRows, setDirtyRows] = useState<string[]>([]);
    const [isSaveQueued, setSaveQueued] = useState<number | null>(null);
    const loadedRecipients = useRef<Record<string, TransferRecipient | null>>({});
    const [error, setError] = useState<string[] | null>(null);
    const hasSeenPagination = useRef(false);

    useEffect(() => {
        const dirty = localStorage.getItem(subAllocationsDirtyKey);
        if (dirty != null) dirtyRowStorageRef.current = JSON.parse(dirty);
        setDirtyRows(Object.keys(dirtyRowStorageRef.current));
    }, []);

    // NOTE(Dan): Sheets are not powered by React, as a result, we must wrap the wallets such that we can pass it
    // down into the cells.
    const walletHolder = useRef<APICallState<PageV2<Wallet>>>(wallets);
    useEffect(() => {
        walletHolder.current = wallets;
    }, [wallets]);
    const allocationHolder = useRef<APICallState<PageV2<SubAllocation>>>(allocations);
    useEffect(() => {
        allocationHolder.current = allocations;
    }, [allocations]);

    useEffect(() => {
        const table = projectLookupTable.current;
        const original = originalRows.current;
        for (const alloc of allocations.data.items) {
            original[alloc.id] = alloc;

            if (!alloc.workspaceIsProject) continue;
            const existing = table[alloc.workspaceTitle];
            if (existing === undefined) {
                table[alloc.workspaceTitle] = [{rows: [alloc], projectId: alloc.workspaceId}];
            } else {
                let found = false;
                for (const entry of existing) {
                    if (entry.projectId === alloc.workspaceId) {
                        entry.rows.push(alloc);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    existing.push({rows: [alloc], projectId: alloc.workspaceId});
                }
            }
        }

        if (allocations.data.next != null) {
            hasSeenPagination.current = true;
        }
    }, [allocations]);

    const sheet = useRef<SheetRenderer>(null);
    const callbacks: SubAllocationCallbacks = useMemo(
        () => ({
            filterByAllocation,
            filterByWorkspace: allocationId => {
                const resolvedAllocation = allocationHolder.current.data.items.find(it => it.id === allocationId);
                if (!resolvedAllocation) return;
                filterByWorkspace(resolvedAllocation.workspaceId, resolvedAllocation.workspaceIsProject);
            }
        }),
        [filterByWorkspace, filterByAllocation]
    );

    const header: string[] = useMemo(() => (
            ["", "Recipient", "", "Product", "Allocation", "Start Date", "End Date", "Amount", "", ""]),
        []
    );

    const cells: SheetCell[] = useMemo(() => ([
        DropdownCell(
            [
                {icon: "projects", title: "Project", value: "PROJECT"},
                {icon: "user", title: "Personal Workspace", value: "USER"},
            ],
            {width: "50px"}
        ),
        TextCell(),
        DropdownCell(
            productTypes.map(type => ({
                icon: productTypeToIcon(type),
                title: productTypeToTitle(type),
                value: type
            })),
            {width: "50px"}
        ),
        FuzzyCell(
            (query, column, row) => {
                const currentType = sheet.current!.readValue(colProductType, row) as ProductType;
                const lq = query.toLowerCase();
                return walletHolder.current.data.items
                    .filter(it => {
                        return it.productType === currentType && it.paysFor.name.toLowerCase().indexOf(lq) != -1;
                    })
                    .map(it => ({
                        icon: productTypeToIcon(it.productType),
                        title: titleForWallet(it),
                        value: titleForWallet(it),
                    }));
            }
        ),
        FuzzyCell(
            (query, column, row) => {
                const currentProduct = sheet.current!.readValue(colProduct, row) ?? "";
                const entries: SheetDropdownOption[] = [];
                for (const wallet of walletHolder.current.data.items) {
                    let pcTitle = titleForWallet(wallet);
                    const isRelevant = currentProduct === "" ||
                        currentProduct === pcTitle;
                    if (!isRelevant) continue;
                    for (const alloc of wallet.allocations) {
                        let allocTitle = `[${alloc.id}] ${pcTitle}`;
                        if (allocTitle.indexOf(query) === -1) continue;

                        entries.push({
                            icon: productTypeToIcon(wallet.productType),
                            title: allocTitle,
                            value: alloc.id,
                            helpText: normalizeBalanceForFrontend(alloc.balance, wallet.productType, wallet.chargeType,
                                    wallet.unit, false, 2) + " " +
                                explainAllocation(wallet.productType, wallet.chargeType, wallet.unit)
                        });
                    }
                }

                return entries;
            }
        ),
        TextCell("Immediately", {fieldType: "date"}),
        TextCell("No expiration", {fieldType: "date"}),
        TextCell(),
        StaticCell((sheetId, coord) => {
            const s = sheet.current;
            if (!s) return "Unknown (Sheet?)";

            const productType = s.readValue(colProductType, coord.row) as ProductType;
            const productId = s.readValue(colProduct, coord.row);
            const resolvedWallet = walletHolder.current.data.items.find(it =>
                titleForWallet(it) === productId && it.productType === productType
            );
            if (!resolvedWallet) return "Unknown";
            return explainAllocation(resolvedWallet.productType, resolvedWallet.chargeType, resolvedWallet.unit);
        }),
        StaticCell(
            (sheetId, coord) => {
                const rowId = sheet.current?.retrieveRowId(coord.row);
                if (!rowId) return "questionSolid";

                if (rowId.indexOf(unsavedPrefix) === 0) {
                    return {
                        contents: "questionSolid",
                        color: "blue",
                        tooltip: "This allocation is new and has never been saved. It is not active."
                    };
                } else {
                    if (dirtyRowStorageRef.current[rowId]) {
                        return {
                            contents: "edit",
                            color: "orange",
                            tooltip: "This allocation is based on an active allocation but changes has not been saved."
                        };
                    } else {
                        return {
                            contents: "check",
                            color: "green",
                            tooltip: "This allocation is active and you have not made any changes to it."
                        };
                    }
                }
            },
            {
                iconMode: true,
                possibleIcons: ["check", "questionSolid", "edit"],
                width: "30px"
            }
        ),
    ]), []);

    useLayoutEffect(() => {
        const s = sheet.current!;
        writeAllocations(s, allocations.data.items, dirtyRowStorageRef.current, sessionId, unsavedRowIds);
    }, [allocations.data.items]);

    const [showHelp, setShowHelp] = useState(false);
    const toggleShowHelp = useCallback((ev) => {
        ev.preventDefault();
        setShowHelp(prev => !prev);
    }, []);

    const discard = useCallback(() => {
        dirtyRowStorageRef.current = {};
        localStorage.removeItem(subAllocationsDirtyKey);
        setDirtyRows([]);
        setError(null);
        writeAllocations(sheet.current!, allocations.data.items, {}, sessionId, unsavedRowIds);
    }, [allocations.data.items]);

    const reload = useCallback(() => {
        const query = document.querySelector<HTMLInputElement>("#resource-search");
        if (query != null) {
            onQuery(query.value);
        }
    }, [onQuery]);

    const save = useCallback(() => {
        const dirtyRows = dirtyRowStorageRef.current;

        const newRows: { update: DepositToWalletRequestItem, rowId: string }[] = [];
        const updatedRows: { update: UpdateAllocationRequestItem, rowId: string }[] = [];
        const deletedRows: { update: UpdateAllocationRequestItem, rowId: string }[] = [];
        const unknownProjects: string[] = [];

        const original = originalRows.current;
        const s = sheet.current;
        if (s === null) return;

        const nowTs = Math.floor(timestampUnixMs());
        const stableKeys = Object.keys(dirtyRows);
        let allValid = true;
        for (const rowId of stableKeys) {
            const row = dirtyRows[rowId];
            const originalRow = original[rowId];

            if (row === "deleted") {
                deletedRows.push({
                    rowId,
                    update: {
                        id: rowId,
                        endDate: nowTs,
                        reason: "Allocation deleted by grant giver",
                        startDate: originalRow?.startDate ?? nowTs,
                        balance: originalRow?.remaining ?? 0
                    }
                });
            } else {
                // Basic row validation
                const rowNumber = s.retrieveRowNumber(rowId);
                if (rowNumber == null) continue;

                let valid = true;

                const newAmount = row[colAmount] == null ? NaN : parseInt(row[colAmount]!);
                const newAmountValid = !isNaN(newAmount) && newAmount >= 0;
                s.registerValidation(
                    colAmount,
                    rowNumber,
                    !newAmountValid ? "invalid" : undefined,
                    "This is not a valid number (the number must be a whole integer)"
                );
                valid = valid && newAmountValid;

                const startDate = row[colStartDate] == null ? null : parseDateFromInput(row[colStartDate]!);
                const endDate = row[colEndDate] == null ? null : parseDateFromInput(row[colEndDate]!);
                const startDateValid = startDate != null;
                const endDateValid = row[colEndDate] === "" || row[colEndDate] === null || endDate != null;
                s.registerValidation(colStartDate, rowNumber, !startDateValid ? "invalid" : undefined);
                s.registerValidation(colEndDate, rowNumber, !endDateValid ? "invalid" : undefined);
                valid = valid && startDateValid && endDateValid;

                const isProject = row[colRecipientType] === "PROJECT";
                const recipient = row[colRecipient];
                const recipientIsValid = recipient != null && recipient.length > 0;
                s.registerValidation(colRecipient, rowNumber, !recipientIsValid ? "invalid" : undefined);
                valid = valid && recipientIsValid;

                const productType = row[colProductType] as ProductType;
                const product = row[colProduct];
                const resolvedWallet = walletHolder.current.data.items.find(wallet => {
                    return titleForWallet(wallet) === product && wallet.productType === productType
                });
                const productIsValid = resolvedWallet != null;
                s.registerValidation(colProduct, rowNumber, !productIsValid ? "invalid" : undefined);
                valid = valid && productIsValid;

                const allocation = row[colAllocation];
                const allocationIsValid = resolvedWallet?.allocations?.some(it => it.id === allocation) === true;
                s.registerValidation(colAllocation, rowNumber, !allocationIsValid ? "invalid" : undefined);
                valid = valid && allocationIsValid;

                let resolvedRecipient: string | null = null;
                if (isProject && recipient != null) {
                    if (originalRow?.workspaceTitle === recipient) {
                        resolvedRecipient = originalRow.workspaceId;
                    } else {
                        const entries = projectLookupTable.current[recipient];
                        if (entries === undefined || entries.length === 0) {
                            const loaded = loadedRecipients.current[recipient];
                            if (loaded !== undefined) {
                                if (loaded === null || !loaded.isProject) {
                                    s.registerValidation(
                                        colRecipient,
                                        rowNumber,
                                        "invalid",
                                        "Unknown project, enter the full path to resolve."
                                    );
                                } else {
                                    resolvedRecipient = loaded.id;
                                    s.registerValidation(colRecipient, rowNumber, "valid");
                                }
                            } else {
                                s.registerValidation(colRecipient, rowNumber, "loading");
                                unknownProjects.push(recipient);
                            }
                        } else if (entries.length === 1) {
                            resolvedRecipient = entries[0].projectId;
                        } else {
                            // NOTE(Dan): In this case, we have a conflict. The user needs to specify the full path to
                            // resolve this conflict.

                            s.registerValidation(
                                colRecipient,
                                rowNumber,
                                "warning",
                                "Found more than one project with this name, enter the full path to resolve."
                            );
                        }
                    }
                } else if (!isProject) {
                    resolvedRecipient = recipient;
                }
                valid = valid && resolvedRecipient != null;

                async function lookupRecipients() {
                    const promises: [string, Promise<TransferRecipient>][] = unknownProjects.map(p =>
                        [p, callAPI<TransferRecipient>(retrieveRecipient({query: p}))]);
                    for (const [p, promise] of promises) {
                        try {
                            loadedRecipients.current[p] = await promise;
                        } catch (e) {
                            loadedRecipients.current[p] = null;
                        }
                    }
                    setSaveQueued(q => (q ?? 0) + 1);
                }

                if (unknownProjects.length > 0) { // noinspection JSIgnoredPromiseFromCall
                    lookupRecipients();
                }

                if (!valid) {
                    allValid = false;
                    continue;
                }

                const asDeletion: UpdateAllocationRequestItem = {
                    id: rowId,
                    endDate: nowTs,
                    reason: "Allocation deleted by grant giver",
                    startDate: originalRow?.startDate ?? nowTs,
                    balance: originalRow?.remaining ?? 0
                };

                const resolvedAmount = normalizeBalanceForBackend(
                    newAmount!, resolvedWallet!.productType, resolvedWallet!.chargeType, resolvedWallet!.unit);

                const asCreation: DepositToWalletRequestItem = {
                    recipient: (isProject ?
                            {type: "project", projectId: resolvedRecipient!} :
                            {type: "user", username: resolvedRecipient!}
                    ),
                    startDate: startDate!,
                    endDate: endDate ?? undefined,
                    description: "Allocation created manually by grant giver",
                    sourceAllocation: allocation!,
                    amount: resolvedAmount
                };

                const asUpdate: UpdateAllocationRequestItem = {
                    id: originalRow?.id ?? "",
                    startDate: startDate!,
                    endDate: endDate ?? undefined,
                    balance: resolvedAmount,
                    reason: "Allocation updated by grant giver"
                };

                const isNewRow = rowId.indexOf(unsavedPrefix) === 0;
                if (isNewRow) {
                    newRows.push({rowId, update: asCreation});
                } else {
                    const entityDidChange = isProject != originalRow.workspaceIsProject ||
                        resolvedRecipient != originalRow.workspaceId;

                    const oldPath = originalRow.path.split(".");
                    const oldAllocation = oldPath[oldPath.length - 2];
                    const allocationDidChange = oldAllocation !== allocation;

                    if (entityDidChange || allocationDidChange) {
                        deletedRows.push({rowId, update: asDeletion});
                        newRows.push({rowId, update: asCreation});
                    } else {
                        updatedRows.push({rowId, update: asUpdate});
                    }
                }
            }
        }

        if (!allValid) return;

        async function attemptSave(startIdx: number, endIdx: number, dry: boolean): Promise<string | null> {
            const update: UpdateAllocationRequestItem[] = [];
            const create: DepositToWalletRequestItem[] = [];

            for (let i = startIdx; i < endIdx; i++) {
                const rowId = stableKeys[i];
                for (const r of updatedRows) {
                    r.update.dry = dry;
                    if (r.rowId === rowId) update.push(r.update);
                }
                for (const r of deletedRows) {
                    r.update.dry = dry;
                    if (r.rowId === rowId) update.push(r.update);
                }
                for (const r of newRows) {
                    r.update.dry = dry;
                    if (r.rowId === rowId) create.push(r.update);
                }
            }

            const createResult: Promise<any> = create.length > 0 ? callAPI(deposit(bulkRequestOf(...create))) : Promise.resolve();
            const updateResult: Promise<any> = update.length > 0 ? callAPI(updateAllocation(bulkRequestOf(...update))) : Promise.resolve();

            try {
                await createResult;
            } catch (e) {
                return e.response?.why ?? "Update failed";
            }

            try {
                await updateResult;
            } catch (e) {
                return e.response?.why ?? "Update failed";
            }

            return null;
        }

        async function saveOrFail() {
            const rootErrorMessage = await attemptSave(0, stableKeys.length, true);
            if (rootErrorMessage == null) {
                await attemptSave(0, stableKeys.length, false);
                const query = document.querySelector<HTMLInputElement>("#resource-search");
                if (query != null) {
                    onQuery(query.value);
                    discard();
                }
            } else {
                const batchSize = Math.ceil(Math.max(1, stableKeys.length / 10));
                if (batchSize > 0) {
                    const errors: Promise<string | null>[] = [];
                    for (let i = 0; i < stableKeys.length; i += batchSize) {
                        errors.push(
                            attemptSave(
                                i,
                                Math.min(stableKeys.length, i + batchSize),
                                true
                            )
                        );
                    }

                    const resolvedErrors = await Promise.all(errors);
                    const allErrors: string[] = [];
                    const s = sheet.current;
                    if (!s) return;
                    for (let i = 0; i < resolvedErrors.length; i++) {
                        const err = resolvedErrors[i];
                        if (err == null) continue;

                        const startIndex = (i * batchSize);
                        if (startIndex > stableKeys.length) break;
                        const endIndex = (Math.min(stableKeys.length, startIndex + batchSize));

                        const resolvedRows: number[] = [];
                        for (let i = startIndex; i < endIndex; i++) {
                            resolvedRows.push((s.retrieveRowNumber(stableKeys[i]) ?? -1) + 1);
                        }

                        if (resolvedRows.length === 1) {
                            allErrors.push(`Row ${resolvedRows[0]}: ${err}`)
                        } else {
                            allErrors.push(`Rows ${resolvedRows.join(", ")}: ${err}`);
                        }
                    }
                    setError(allErrors);
                }
            }
        }

        if (updatedRows.length > 0 || deletedRows.length > 0 || newRows.length > 0) {
            // noinspection JSIgnoredPromiseFromCall
            saveOrFail();
        }
    }, []);

    useEffect(() => {
        if (isSaveQueued != null) save();
    }, [isSaveQueued, save]);

    const onSearchInput = useCallback((ev: React.KeyboardEvent) => {
        if (ev.code === "Enter" || ev.code === "NumpadEnter") {
            const target = ev.target as HTMLInputElement;
            ev.preventDefault();
            onQuery(target.value);
        }
    }, [onQuery]);

    return <HighlightedCard
        color={"green"}
        icon={"grant"}
        title={"Sub-allocations"}
        subtitle={
            <SearchInput>
                <Input id={"resource-search"} placeholder={"Search in allocations..."} onKeyDown={onSearchInput}/>
                <Label htmlFor={"resource-search"}>
                    <Icon name={"search"} size={"20px"}/>
                </Label>
            </SearchInput>
        }
    >
        <Text color="darkGray" fontSize={1} mb={"16px"}>
            An overview of workspaces which have received a <i>grant</i> or a <i>deposit</i> from you
        </Text>

        <div>
            <Flex flexDirection={"row"} mb={"16px"} alignItems={"center"}>
                <div>
                    <a href="#" onClick={toggleShowHelp}>Spreadsheet help</a>
                    {!showHelp ? null :
                        <ul>
                            <li><b>←, ↑, →, ↓, Home, End:</b> Movement</li>
                            <li><b>Shift + Movement Key:</b> Select multiple</li>
                            <li><b>Ctrl/Cmd + C:</b> Copy</li>
                            <li><b>Ctrl/Cmd + V:</b> Paste</li>
                            <li><b>Right click menu:</b> Insert rows, clone and more...</li>
                        </ul>
                    }
                </div>

                <Box flexGrow={1}/>

                {allocations.data.next == null ?
                    (hasSeenPagination.current ?
                        <Button mr={"32px"} height={"35px"} onClick={reload}>Reload</Button> : null) :
                    <Button mr={"32px"} height={"35px"} onClick={loadMore}>Load more</Button>
                }

                {dirtyRows.length === 0 ? <i>No unsaved changes</i> :
                    <>
                        <Flex flexDirection={"row"} mr={"32px"} alignItems={"center"}>
                            <i>Viewing local draft</i>
                            <UITooltip
                                tooltipContentWidth="260px"
                                trigger={
                                    <Circle>
                                        <Text mt="-3px" ml="5px">?</Text>
                                    </Circle>
                                }
                            >
                                <Text textAlign={"left"}>
                                    <p>
                                        Your draft has been saved. You can safely leave the page and come
                                        back later.
                                    </p>

                                    <p>
                                        Your changes won't take effect until you press the green <b>'Save'</b>{" "}
                                        button.
                                    </p>
                                </Text>
                            </UITooltip>
                        </Flex>
                        <ConfirmCancelButtons onConfirm={save} onCancel={discard} confirmText={"Save"}
                                              cancelText={"Discard"}/>
                    </>
                }
            </Flex>

            {error == null ? null : <div>
                <b>An error occurred while attempting to save your data:</b>

                <ul>
                    {error.map((err, idx) => <li key={idx}>{err}</li>)}
                </ul>
            </div>}
        </div>
        <Box mb={"16px"} minHeight={"500px"} maxHeight={"calc(100vh - 300px)"} overflowY={"auto"}>
            <Sheet
                header={header}
                cells={cells}
                renderer={sheet}
                newRowPrefix={unsavedPrefix + sessionId + "-sh"}
                extra={callbacks}
                operations={(defaults) => [...subAllocationOperations, ...defaults]}
                onRowDeleted={(rowId) => {
                    const storage = dirtyRowStorageRef.current;
                    if (rowId.indexOf(unsavedPrefix) === 0) {
                        // NOTE(Dan): Nothing special to do, we can just delete it from storage
                        delete storage[rowId];
                    } else {
                        // NOTE(Dan): We need to keep a record which indicates that we have deleted the entry.
                        storage[rowId] = "deleted";
                        setDirtyRows(rows => {
                            if (rows.indexOf(rowId) !== -1) return rows;
                            return [...rows, rowId];
                        });
                    }

                    localStorage.setItem(subAllocationsDirtyKey, JSON.stringify(storage));
                }}
                onRowUpdated={(rowId, row, values) => {
                    const s = sheet.current!;

                    const storage = dirtyRowStorageRef.current;
                    storage[rowId] = values;
                    localStorage.setItem(subAllocationsDirtyKey, JSON.stringify(storage));

                    setDirtyRows(rows => {
                        if (rows.indexOf(rowId) !== -1) return rows;
                        s.writeValue(colStatus, row, undefined);
                        return [...rows, rowId];
                    });

                    s.writeValue(colUnit, row, undefined);
                }}
            />
        </Box>
    </HighlightedCard>;
};

interface SubAllocationCallbacks {
    filterByAllocation: (allocationId: string) => void;
    filterByWorkspace: (allocationId: string) => void;
}

const subAllocationOperations: Operation<never, SheetCallbacks>[] = [{
    icon: "filterSolid",
    text: "Focus on allocation",
    enabled: (selected, cb) => {
        const rowId = cb.sheet.retrieveRowId(cb.sheet.contextMenuTriggeredBy);
        return !(rowId == null || rowId.indexOf(unsavedPrefix) === 0);
    },
    onClick: (selected, cb) => {
        const extraCb = cb.extra! as SubAllocationCallbacks;
        const rowId = cb.sheet.retrieveRowId(cb.sheet.contextMenuTriggeredBy)!;
        extraCb.filterByAllocation(rowId);
    },
}, {
    icon: "filterSolid",
    text: "Focus on workspace",
    enabled: (selected, cb) => {
        const rowId = cb.sheet.retrieveRowId(cb.sheet.contextMenuTriggeredBy);
        return !(rowId == null || rowId.indexOf(unsavedPrefix) === 0);
    },
    onClick: (selected, cb) => {
        const extraCb = cb.extra! as SubAllocationCallbacks;
        const rowId = cb.sheet.retrieveRowId(cb.sheet.contextMenuTriggeredBy)!;
        extraCb.filterByWorkspace(rowId);
    },
}];

const SearchInput = styled.div`
  position: relative;

  label {
    position: absolute;
    left: 250px;
    top: 10px;
  }
`;