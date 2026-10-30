(function () {
  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();
  var p0 = style.getPropertyValue('--p0').trim();
  var p1 = style.getPropertyValue('--p1').trim();
  var p2 = style.getPropertyValue('--p2').trim();

  // --- Mermaid: blocker flow ---
  if (window.mermaid) {
    mermaid.initialize({
      startOnLoad: false,
      theme: 'base',
      securityLevel: 'loose',
      fontFamily: "'Instrument Sans', 'Microsoft YaHei', 'PingFang SC', sans-serif",
      themeVariables: {
        primaryColor: '#edf2ff',
        primaryTextColor: ink,
        primaryBorderColor: accent,
        lineColor: '#8fa3c4',
        secondaryColor: '#fff1e7',
        tertiaryColor: '#f4f7fb',
        fontSize: '13px'
      },
      flowchart: { curve: 'basis', padding: 16, nodeSpacing: 34, rankSpacing: 46 }
    });
    mermaid.run();
  }

  function initChart(id) {
    var el = document.getElementById(id);
    if (!el) return null;
    var chart = echarts.init(el, null, { renderer: 'svg' });
    window.addEventListener('resize', function () { chart.resize(); });
    return chart;
  }

  // --- Chart: severity distribution ---
  var sev = initChart('chart-severity');
  if (sev) {
    sev.setOption({
      animation: false,
      tooltip: { trigger: 'item', appendToBody: true, formatter: '{b}：{c} 项（{d}%）' },
      title: {
        text: '26',
        subtext: '确认缺陷',
        left: '50%',
        top: '37%',
        textAlign: 'center',
        textStyle: { color: ink, fontSize: 30, fontWeight: 700, fontFamily: 'Instrument Sans' },
        subtextStyle: { color: muted, fontSize: 11, fontFamily: 'Instrument Sans' }
      },
      series: [{
        type: 'pie',
        radius: ['52%', '74%'],
        center: ['50%', '52%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: bg2, borderWidth: 4, borderRadius: 4 },
        label: { show: true, formatter: '{b}\n{c} 项', color: ink, fontSize: 11.5, lineHeight: 17 },
        labelLine: { length: 12, length2: 10, lineStyle: { color: rule } },
        data: [
          { value: 3, name: 'P0 阻断', itemStyle: { color: p0 } },
          { value: 9, name: 'P1 高', itemStyle: { color: p1 } },
          { value: 14, name: 'P2 中低', itemStyle: { color: p2 } }
        ]
      }]
    });
  }

  // --- Chart: defects by module ---
  var mod = initChart('chart-modules');
  if (mod) {
    var modData = [
      { name: '预约核心', value: 6 },
      { name: '小程序前端', value: 4 },
      { name: '工程与运维', value: 4 },
      { name: '账号与权限', value: 3 },
      { name: '管理端前端', value: 3 },
      { name: '安全与可见性', value: 3 },
      { name: '签到与取消', value: 2 },
      { name: '统计报表', value: 1 }
    ];
    mod.setOption({
      animation: false,
      grid: { left: 92, right: 34, top: 16, bottom: 24 },
      tooltip: { trigger: 'axis', appendToBody: true, axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: rule, type: 'dashed' } },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: muted, fontSize: 11 }
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: modData.map(function (d) { return d.name; }),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: ink, fontSize: 12 }
      },
      series: [{
        type: 'bar',
        data: modData.map(function (d, i) {
          return {
            value: d.value,
            itemStyle: {
              color: i < 2 ? accent : accent + (i < 5 ? 'cc' : '99'),
              borderRadius: [0, 4, 4, 0]
            }
          };
        }),
        barWidth: '52%',
        label: { show: true, position: 'right', color: muted, fontSize: 11.5, formatter: '{c}' }
      }]
    });
  }

  // --- Chart: verification coverage radar ---
  var radar = initChart('chart-radar');
  if (radar) {
    radar.setOption({
      animation: false,
      tooltip: { trigger: 'item', appendToBody: true },
      radar: {
        center: ['50%', '52%'],
        radius: '66%',
        splitNumber: 4,
        indicator: [
          { name: '接口功能', max: 100 },
          { name: '权限与越权', max: 100 },
          { name: '预约规则边界', max: 100 },
          { name: '数据一致性', max: 100 },
          { name: '异常与容错', max: 100 },
          { name: '前端构建与联调', max: 100 }
        ],
        axisName: { color: muted, fontSize: 11 },
        splitLine: { lineStyle: { color: rule } },
        splitArea: { show: false },
        axisLine: { lineStyle: { color: rule } }
      },
      series: [{
        type: 'radar',
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { color: accent, width: 2 },
        itemStyle: { color: accent },
        areaStyle: { color: accent + '1f' },
        data: [{
          name: '验证覆盖度',
          value: [88, 55, 45, 80, 55, 78]
        }]
      }]
    });
  }
})();
