import { chromium, expect } from '@playwright/test';
const browser = await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1050}});
const errors=[]; page.on('pageerror',e=>errors.push(e.message));
let logged=true;const mutations=[];
const meta={period:'test-period',period_start:1760000000,period_end:1761209600,period_days:14,next_period_days:14,tiers:{L1:{quota:500000000,models:[]},L2:{quota:1500000000,models:[]}},prices:{}};
await page.route('**/api/**', async route=>{
 const url=new URL(route.request().url());let data={ok:true};let status=200;
 if(url.pathname==='/api/session') {status=logged?200:401;data=logged?{ok:true,csrf:'test-csrf'}:{ok:false};}
 else if(url.pathname==='/api/login') {logged=true;data.csrf='test-csrf';}
 else if(url.pathname==='/api/logout') logged=false;
 else if(url.pathname.endsWith('/meta')) data=meta;
 else if(url.pathname.endsWith('/accounts')) data={accounts:url.searchParams.get('dimension')==='department'?[{subject:'研发部',base_tier:'L1',models:[]}]:[]};
 else if(url.pathname.endsWith('/audit')) data={records:[]};
 if(route.request().method()==='POST') {
  const body=route.request().postDataJSON();
  mutations.push({path:url.pathname,csrf:route.request().headers()['x-portal-csrf'],body});
  if(url.pathname.endsWith('/cycle')) meta.next_period_days=body.days;
  if(url.pathname.endsWith('/reset')) {meta.period='reset-period';meta.period_days=meta.next_period_days;}
 }
 await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
});
await page.route('**/user/info?*', route=>route.fulfill({json:{data:{username:'test',displayName:'Test Admin',type:'admin'}}}));
await page.route('**/system/info?*', route=>route.fulfill({json:{data:{}}}));
await page.route('**/system/config?*', route=>route.fulfill({json:{data:{mode:'K8S'}}}));
await page.goto((process.env.CONSOLE_TEST_URL || 'http://127.0.0.1:18922') + '/walmart-quota');
await expect(page.getByRole('heading',{name:'用量总览',exact:true})).toBeVisible();
await page.getByRole('link',{name:'档位配置',exact:true}).click();
await page.getByRole('button',{name:'编辑档位模板',exact:true}).click();
await expect(page.getByRole('dialog').getByText('L1',{exact:true})).toBeVisible();
await expect(page.getByRole('dialog').getByText('L2',{exact:true})).toBeVisible();
if(await page.getByRole('dialog').getByText('L3',{exact:true}).count()) throw new Error('Removed L3 tier is still rendered');
await page.getByRole('dialog').getByRole('button',{name:'取消'}).click();
await page.getByRole('spinbutton',{name:'下一周期天数'}).fill('7');
await page.getByRole('button',{name:'保存周期'}).click();
await expect(page.getByRole('spinbutton',{name:'下一周期天数'})).toHaveValue('7');
const selectedDay=await page.getByLabel('重置开始日').inputValue();
const selectedEnd=new Date(`${selectedDay}T00:00:00Z`);
selectedEnd.setUTCDate(selectedEnd.getUTCDate()+4);
const yesterday=new Date(`${selectedDay}T00:00:00Z`);
yesterday.setUTCDate(yesterday.getUTCDate()-1);
await page.getByLabel('重置结束日').fill(yesterday.toISOString().slice(0,10));
await page.getByRole('button',{name:'立即重置周期'}).click();
await expect(page.getByRole('alert')).toContainText('启止日须包含上海时区的今天');
if(mutations.some(m=>m.path.endsWith('/reset'))) throw new Error('Invalid reset date reached API');
await page.getByLabel('重置结束日').fill(selectedEnd.toISOString().slice(0,10));
page.once('dialog',dialog=>dialog.accept());
await page.getByRole('button',{name:'立即重置周期'}).click();
await expect.poll(()=>mutations.find(m=>m.path.endsWith('/reset'))).toBeTruthy();
const resetMutation=mutations.find(m=>m.path.endsWith('/reset'));
if(resetMutation.body.start_date!==selectedDay || resetMutation.body.end_date!==selectedEnd.toISOString().slice(0,10)) throw new Error('Reset date range not submitted');
await expect(page.getByText('7 天 / 自动重置')).toBeVisible();
await page.evaluate(()=>{const sibling=document.createElement('button');sibling.id='console-style-probe';sibling.textContent='Console';document.body.append(sibling);});
await page.getByRole('link',{name:'用户与应用',exact:true}).click();
await page.getByRole('button',{name:'批量导入',exact:true}).click();
await page.getByLabel('选择 CSV 文件').setInputFiles({name:'test.csv',mimeType:'text/csv',buffer:Buffer.from('用户标识,所属部门,档位\n001,研发部,L1\n')});
await expect(page.getByText('待导入',{exact:true})).toBeVisible();
await page.getByRole('checkbox').check();
await page.getByRole('button',{name:'确认导入',exact:true}).click();
await expect(page.getByText('已生效',{exact:true})).toBeVisible();
if(!mutations.some(m=>m.path.startsWith('/api/quota/')&&m.csrf==='test-csrf'&&m.body.subject==='001')) throw new Error('Missing CSRF/string-ID import mutation');
const style=await page.locator('#console-style-probe').evaluate(el=>({display:getComputedStyle(el).display,color:getComputedStyle(el).color}));
if(style.display==='inline-flex') throw new Error('Quota button CSS leaked');
if(errors.length) throw new Error(errors.join('\n'));
console.log(JSON.stringify({passed:['shared React mounts','Console session','Console native routes','two-tier editor without L3','cycle configuration and reset','CSV preview and import','CSRF and string ID preserved','sibling CSS isolated','no page errors'],mutations:mutations.map(m=>m.path),siblingStyle:style},null,2));
await page.screenshot({path:new URL('../docs/evidence/quota-console-migration.png', import.meta.url).pathname});
await browser.close();
