// Custom quota navigation stays separate from upstream Console menus.
export default {
  name: 'Walmart 额度管理',
  path: '/walmart-quota',
  children: [
    { name: '用量总览', path: '/walmart-quota/overview' },
    { name: '用户与应用', path: '/walmart-quota/accounts' },
    { name: '部门模板', path: '/walmart-quota/departments' },
    { name: '档位配置', path: '/walmart-quota/tiers' },
    { name: '模型单价', path: '/walmart-quota/prices' },
    { name: '变更审计', path: '/walmart-quota/audit' },
  ],
};
