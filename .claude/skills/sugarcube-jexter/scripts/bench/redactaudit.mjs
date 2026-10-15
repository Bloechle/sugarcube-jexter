// redactaudit.mjs — the Audit tab folded into Redact. What this keeps from breaking:
//
//   1. the tab is GONE and nothing else lost a tab
//   2. the verdict runs BY ITSELF on entering the tool — no command pressed
//   3. and it writes NOTHING: no ocd/redact.json in the container, so an Export cannot repair a
//      document because somebody looked at it (engine.js applies that member on every export)
//   4. Repair is the one crossing: findings become work, and a repaired finding is drawn ONCE
//   5. Apply → the verdict is asked again and comes back CLEAN (a stale verdict is the worst defect
//      this tool could have)
//   6. a clean document says clean — no false positive
//   7. leaving the tool leaves the page as it was found
import { serve, openPrism, ribbon } from './prism_bench.mjs';

const srv = serve(); await new Promise(d => srv.listen(8110, d));
const out = {};
const tab = (page, id) => page.click(`.px-tooltab[data-tool="${id}"]`);
const read = (page) => page.evaluate(() => ({
  verdict: document.getElementById('rd-verdict')?.textContent,
  method: document.getElementById('rd-method')?.textContent,
  info: document.getElementById('rd-info')?.textContent,
  help: document.querySelector('#ribbon [data-ribbon="redact"] .px-rb-help')?.textContent
     || document.querySelector('#ribbon [data-ribbon="redact"]')?.textContent?.slice(-160),
  groups: [...document.querySelectorAll('#rd-list .rd-group')].map(n => n.textContent),
  findingRows: document.querySelectorAll('#rd-list [data-f]').length,
  itemRows: document.querySelectorAll('#rd-list [data-i]').length,
  member: !!window.prism.state.files?.['OEBPS/ocd/redact.json'],
  cmds: [...document.querySelectorAll('#ribbon [data-ribbon="redact"] .px-rb-btn')].map(b => b.textContent.trim()),
}));
const onPage = (page) => page.evaluate(() => {
  const d = document.querySelector('.prism-frame')?.contentDocument;
  return { find: d?.querySelectorAll('.rd-find').length ?? -1,
           zone: d?.querySelectorAll('.rd-zone').length ?? -1,
           ui:   d?.querySelectorAll('[data-ui]').length ?? -1 };
});

/* 1-5 · the leaky document -------------------------------------------------------------------- */
{
  const { browser, page, errors } = await openPrism({ book: '/fake.ocd.epub', dev: true });
  out.tabs = await page.$$eval('.px-tooltab', bs => bs.map(b => b.dataset.tool));

  await tab(page, 'redact');
  await page.waitForTimeout(2500);                       // the verdict runs by itself: no click here
  out.onEntry = await read(page);
  out.onEntryPage = await onPage(page);

  await ribbon(page, 'redact', 'Repair');
  await page.waitForTimeout(800);
  out.afterRepair = await read(page);
  out.afterRepairPage = await onPage(page);

  await ribbon(page, 'redact', 'Apply');
  await page.waitForTimeout(6000);                       // patch + reload + the verdict asked again
  out.afterApply = await read(page);
  out.afterApplyPage = await onPage(page);

  await tab(page, 'home');
  await page.waitForTimeout(600);
  out.afterLeavePage = await onPage(page);

  out.errors = errors;
  await browser.close();
}

/* 6 · the honest document: nothing hidden, and the tool says so ------------------------------- */
{
  const { browser, page, errors } = await openPrism({ book: '/legit.ocd.epub', dev: true });
  await tab(page, 'redact');
  await page.waitForTimeout(2500);
  out.cleanDoc = await read(page);
  out.cleanDocPage = await onPage(page);
  out.cleanErrors = errors;
  await browser.close();
}

console.log(JSON.stringify(out, null, 1));
srv.close(); process.exit(0);
